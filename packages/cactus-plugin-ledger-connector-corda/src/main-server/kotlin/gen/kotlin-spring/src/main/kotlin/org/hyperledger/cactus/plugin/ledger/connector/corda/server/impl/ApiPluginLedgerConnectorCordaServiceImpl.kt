package org.hyperledger.cactus.plugin.ledger.connector.corda.server.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import net.corda.core.flows.FlowLogic
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowProgressHandle
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.loggerFor
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.xfer.InMemorySourceFile
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.api.ApiPluginLedgerConnectorCordaService
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.model.*
import org.xeustechnologies.jcl.JarClassLoader
import java.io.IOException
import java.io.InputStream
import java.lang.Exception
import java.lang.IllegalStateException
import java.lang.RuntimeException
import java.lang.reflect.Constructor
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.IllegalArgumentException

// TODO Look into this project for powering the connector of ours:
// https://github.com/180Protocol/codaptor
@Service
class ApiPluginLedgerConnectorCordaServiceImpl(
    // FIXME: We already have the code/annotations  set up "the spring boot way" so that credentials do not need
    // to be hardcoded like this. Not even sure if these magic strings here actually get used at all or if spring just
    // overwrites the bean property with whatever it constructed internally based on the configuration.
    // Either way, these magic strings gotta go.
    val rpc: NodeRPCConnection
) : ApiPluginLedgerConnectorCordaService {

    companion object {

        // FIXME: do not recreate the mapper for every service implementation instance that we create...
        val mapper: ObjectMapper = jacksonObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

        val writer: ObjectWriter = mapper.writer()

        val jcl: JarClassLoader = JarClassLoader(ApiPluginLedgerConnectorCordaServiceImpl::class.java.classLoader)

        val logger = loggerFor<ApiPluginLedgerConnectorCordaServiceImpl>()

        // If something is missing from here that's because they also missed at in the documentation:
        // https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
        val exoticTypes: Map<String, Class<*>> = mapOf(

            "byte" to Byte::class.java,
            "char" to Char::class.java,
            "int" to Int::class.java,
            "short" to Short::class.java,
            "long" to Long::class.java,
            "float" to Float::class.java,
            "double" to Double::class.java,
            "boolean" to Boolean::class.java,

            "byte[]" to ByteArray::class.java,
            "char[]" to CharArray::class.java,
            "int[]" to IntArray::class.java,
            "short[]" to ShortArray::class.java,
            "long[]" to LongArray::class.java,
            "float[]" to FloatArray::class.java,
            "double[]" to DoubleArray::class.java,
            "boolean[]" to BooleanArray::class.java
        )
    }

    fun getOrInferType(fqClassName: String): Class<*> {
        Objects.requireNonNull(fqClassName, "fqClassName must not be null for its type to be inferred.")

        return if (exoticTypes.containsKey(fqClassName)) {
            exoticTypes.getOrElse(
                fqClassName,
                { throw IllegalStateException("Could not locate Class<*> for $fqClassName Exotic JVM types map must have been modified on a concurrent threat.") })
        } else {
            try {
                jcl.loadClass(fqClassName, true)
            } catch (ex: ClassNotFoundException) {
                Class.forName(fqClassName)
            }
        }
    }

    fun instantiate(jvmObject: JvmObject): Any? {
        logger.info("Instantiating ... JvmObject={}", jvmObject)

        val clazz = getOrInferType(jvmObject.jvmType.fqClassName)

        when (jvmObject.jvmTypeKind) {
            JvmTypeKind.REFERENCE -> {
                if (jvmObject.jvmCtorArgs == null) {
                    throw IllegalArgumentException("jvmObject.jvmCtorArgs cannot be null when jvmObject.jvmTypeKind == JvmTypeKind.REFERENCE")
                }
                val constructorArgs: Array<Any?> = jvmObject.jvmCtorArgs.map { x -> instantiate(x) }.toTypedArray()

                when {
                    List::class.java.isAssignableFrom(clazz) -> {
                        return listOf(*constructorArgs)
                    }
                    Currency::class.java.isAssignableFrom(clazz) -> {
                        // FIXME introduce a more dynamic/flexible way of handling classes with no public constructors....
                        return Currency.getInstance(jvmObject.jvmCtorArgs.first().primitiveValue as String)
                    }
                    Array<Any>::class.java.isAssignableFrom(clazz) -> {
                        // TODO verify that this actually works and also
                        // if we need it at all since we already have lists covered
                        return arrayOf(*constructorArgs)
                    }
                    else -> {
                        val constructorArgTypes: List<Class<*>> =
                            jvmObject.jvmCtorArgs.map { x -> getOrInferType(x.jvmType.fqClassName) }
                        val constructor: Constructor<*>
                        try {
                            constructor = clazz.constructors
                                .filter { c -> c.parameterCount == constructorArgTypes.size }
                                .single { c ->
                                    c.parameterTypes
                                        .mapIndexed { index, clazz -> clazz.isAssignableFrom(constructorArgTypes[index]) }
                                        .all { x -> x }
                                }
                        } catch (ex: NoSuchElementException) {
                            val argTypes = jvmObject.jvmCtorArgs.joinToString(",") { x -> x.jvmType.fqClassName }
                            val className = jvmObject.jvmType.fqClassName
                            val constructorsAsStrings = clazz.constructors
                                .mapIndexed { i, c -> "$className->Constructor#${i + 1}(${c.parameterTypes.joinToString { p -> p.name }})" }
                                .joinToString(" ;; ")
                            val targetConstructor = "Cannot find matching constructor for ${className}(${argTypes})"
                            val availableConstructors =
                                "Searched among the ${clazz.constructors.size} available constructors: $constructorsAsStrings"
                            throw RuntimeException("$targetConstructor --- $availableConstructors")
                        }

                        logger.info("Constructor=${constructor}")
                        constructorArgs.forEachIndexed { index, it -> logger.info("Constructor ARGS: #${index} -> $it") }
                        val instance = constructor.newInstance(*constructorArgs)
                        logger.info("Instantiated REFERENCE OK {}", instance)
                        return instance
                    }
                }

            }
            JvmTypeKind.PRIMITIVE -> {
                logger.info("Instantiated PRIMITIVE OK {}", jvmObject.primitiveValue)
                return jvmObject.primitiveValue
            }
            else -> {
                throw IllegalArgumentException("Unknown jvmObject.jvmTypeKind (${jvmObject.jvmTypeKind})")
            }
        }
    }

    fun dynamicInvoke(rpc: CordaRPCOps, req: InvokeContractV1Request): InvokeContractV1Response {
        @Suppress("UNCHECKED_CAST")
        val classFlowLogic = getOrInferType(req.flowFullClassName) as Class<out FlowLogic<*>>
        val params = req.params.map { p -> instantiate(p) }.toTypedArray()
        logger.info("params={}", params)

        val flowHandle = when (req.flowInvocationType) {
            FlowInvocationType.TRACKED_FLOW_DYNAMIC -> rpc.startTrackedFlowDynamic(classFlowLogic, *params)
            FlowInvocationType.FLOW_DYNAMIC -> rpc.startFlowDynamic(classFlowLogic, *params)
        }

        val timeoutMs: Long = req.timeoutMs?.toLong() ?: 60000
        logger.debug("Invoking flow with timeout of $timeoutMs ms ...")
        val progress: List<String> = when (req.flowInvocationType) {
            FlowInvocationType.TRACKED_FLOW_DYNAMIC -> (flowHandle as FlowProgressHandle<*>)
                .progress
                .toList()
                .toBlocking()
                .first()
            FlowInvocationType.FLOW_DYNAMIC -> emptyList()
        }
        logger.debug("Starting to wait for flow completion now...")
        val returnValue = flowHandle.returnValue.get(timeoutMs, TimeUnit.MILLISECONDS)
        val id = flowHandle.id

        logger.info("Progress(${progress.size})={}", progress)
        logger.info("ReturnValue={}", returnValue)
        logger.info("Id=$id")
        // FIXME: If the full return value (SignedTransaction instance) gets returned as "returnValue"
        // then Jackson crashes like this:
        // 2021-03-01 06:58:25.608 ERROR 7 --- [nio-8080-exec-7] o.a.c.c.C.[.[.[/].[dispatcherServlet]:
        // Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception
        // [Request processing failed; nested exception is org.springframework.http.converter.HttpMessageNotWritableException:
        // Could not write JSON: Failed to deserialize group OUTPUTS_GROUP at index 0 in transaction:
        // net.corda.samples.obligation.states.IOUState: Interface net.corda.core.contracts.LinearState
        // requires a field named participants but that isn't found in the schema or any superclass schemas;
        // nested exception is com.fasterxml.jackson.databind.JsonMappingException:
        // Failed to deserialize group OUTPUTS_GROUP at index 0 in transaction: net.corda.samples.obligation.states.IOUState:
        // Interface net.corda.core.contracts.LinearState requires a field named participants but that isn't found in
        // the schema or any superclass schemas (through reference chain:
        // org.hyperledger.cactus.plugin.ledger.connector.corda.server.model.InvokeContractV1Response["returnValue"]->
        // net.corda.client.jackson.internal.StxJson["wire"]->net.corda.client.jackson.internal.WireTransactionJson["outputs"])]
        // with root cause
        return InvokeContractV1Response(id.toString(), progress, (returnValue as SignedTransaction).id)
    }

    // FIXME - make it clear in the documentation that this deployment endpoint is not recommended for production
    // because it does not support taking all the precautionary steps that are recommended by the official docs here
    // https://docs.corda.net/docs/corda-enterprise/4.6/node-upgrade-notes.html#step-1-drain-the-node
    // The other solution is of course to make it so that this endpoint is a fully fledged, robust, production ready
    // implementation and that would be preferred over the longer term, but maybe it's actually just scope creep...
    override fun deployContractJarsV1(deployContractJarsV1Request: DeployContractJarsV1Request?): DeployContractJarsSuccessV1Response {
        if (deployContractJarsV1Request == null) {
            throw IllegalArgumentException("DeployContractJarsV1Request cannot be null")
        }
        try {
            val decoder = Base64.getDecoder()

            deployContractJarsV1Request.cordappDeploymentConfigs.forEachIndexed { index, cdc ->
                val cred = cdc.sshCredentials
                logger.debug("Creating new SSHClient object for CordappDeploymentConfig #$index...")
                val ssh = SSHClient()
                // FIXME we need to support host key verification as a minimum in order to claim that we
                // are secure by default
                // ssh.addHostKeyVerifier(cred.hostKeyEntry)
                ssh.addHostKeyVerifier(PromiscuousVerifier())

                logger.debug("Connecting with new SSHClient object for CordappDeploymentConfig #$index...")
                val maxTries = 10;
                val tryIntervalMs = 1000L;
                var tries = 0

                // TODO: pull this out to be a class level function instead of an inline one
                fun tryConnectingToSshHost () {
                    tries++
                    try {
                        ssh.connect(cred.hostname, cred.port)
                    } catch (ex: TransportException) {
                        if (tries < maxTries) {
                            Thread.sleep(tryIntervalMs)
                            tryConnectingToSshHost()
                        } else {
                            throw RuntimeException("Fed up after $maxTries retries while connecting to SSH host:", ex)
                        }
                    }
                }
                tryConnectingToSshHost()

                try {
                    // FIXME - plain text passwords sent in in the request is the worst possible solution (but was also
                    // the one that we could quickly get to work with)
                    // Need to implement public key authentication, also need to support pulling credentials from the keychain
                    // at least support pulling the private key being retrieved from the keychain and also to be specified
                    // as a file on the node's file system and also as an environment variable.
                    // Also need to document which one of these options is the most secure and that one has to be the default
                    // so that we are adhering to the "secure by default" design principle of ours.
                    ssh.auth(cred.username, AuthPassword(PasswordUtils.createOneOff(cred.password.toCharArray())))

                    logger.debug("Deploying to Node {} at host {}:{}:{}", index, cred.hostname, cred.port, cdc.cordappDir)

                    try {
                        val nodeRPCConnection = NodeRPCConnection(
                            cdc.rpcCredentials.hostname,
                            cdc.rpcCredentials.username,
                            cdc.rpcCredentials.password,
                            cdc.rpcCredentials.port
                        )

                        nodeRPCConnection.initialiseNodeRPCConnection()
                        nodeRPCConnection.gracefulShutdown()

                    } catch (ex: Exception) {
                        throw RuntimeException("Failed to gracefully shut down the node prior to cordapp deployment onto ${cred.hostname}:${cred.port}:${cdc.cordappDir}", ex)
                    }

                    try {
                        deployContractJarsV1Request.jarFiles.map {
                            val jarFileString = decoder.decode(it.contentBase64)

                            // TODO refactor this: write an actual class that implements the interface and then use that
                            // instead of creating anonymous classes inline...
                            val localSourceFile = object : InMemorySourceFile() {

                                private val filename = it.filename
                                private val contentBase64 = it.contentBase64
                                private val byteArray: ByteArray = decoder.decode(contentBase64)
                                private val inputStream: InputStream

                                init {
                                    inputStream = byteArray.inputStream()
                                }

                                override fun getName(): String {
                                    return filename
                                }

                                override fun getLength(): Long {
                                    val jarFileLength = byteArray.size.toLong()
                                    logger.debug("jarFileLength: $jarFileLength for $filename")
                                    return jarFileLength
                                }

                                override fun getInputStream(): InputStream {
                                    return inputStream
                                }
                            }

                            val taskDescription = "SCP upload ${it.filename} (size=${jarFileString.size}) onto ${cred.hostname}:${cred.port}:${cdc.cordappDir}"
                            logger.debug("Starting $taskDescription")
                            ssh.newSCPFileTransfer().upload(localSourceFile, cdc.cordappDir)
                            logger.debug("Finished $taskDescription")

                            if (it.hasDbMigrations) {
                                logger.debug("${it.filename} has db migrations declared, executing those now...")
                                val session = ssh.startSession()
                                session.allocateDefaultPTY()
                                val migrateCmd = "java -jar ${cdc.cordaJarPath} run-migration-scripts --app-schemas --base-directory=${cdc.nodeBaseDirPath}"
                                logger.debug("migrateCmd=$migrateCmd")
                                val migrateCmdRes = session.exec(migrateCmd)
                                val migrateCmdOut = net.schmizz.sshj.common.IOUtils.readFully(migrateCmdRes.inputStream).toString()
                                logger.debug("migrateCmdOut=${migrateCmdOut}")
                                session.close()
                                logger.debug("Closed the db migrations CMD SSH session successfully.")
                            }
                            it.filename
                        }
                    } catch (ex: Exception) {
                        throw RuntimeException("Failed to upload jars to corda node.", ex)
                    }

                    val session: Session = ssh.startSession()
                    try {
                        val startNodeTask = "Starting of Corda node ${cred.hostname}:${cred.port} with CMD=${cdc.cordaNodeStartCmd}"
                        logger.debug("$startNodeTask ...")
                        session.allocateDefaultPTY()
                        val startNodeRes = session.exec(cdc.cordaNodeStartCmd)
                        val startNodeOut = net.schmizz.sshj.common.IOUtils.readFully(startNodeRes.inputStream).toString()
                        logger.debug("$startNodeTask successfully finished with: {}", startNodeOut)
                    } catch (ex: Exception) {
                        throw RuntimeException("Failed to start the node after the cordapp deployment onto ${cred.hostname}:${cred.port}:${cdc.cordappDir}", ex)
                    } finally {
                        try {
                            session.close()
                            logger.debug("Closed Corda Start CMD SSH session successfully.")
                        } catch (e: IOException) {
                            logger.warn("SSH session failed to close, but this might be normal based on the SSHJ docs/examples: ", e)
                        }
                    }
                } finally {
                    logger.debug("Disconnecting from SSH host ${cred.hostname}:${cred.port}...")
                    try {
                        ssh.disconnect()
                        logger.debug("Disconnected OK from SSH host ${cred.hostname}:${cred.port}")
                    } catch (ex: Exception) {
                        logger.warn("Disconnect failed from SSH host ${cred.hostname}:${cred.port}. Ignoring since we are done anyway...")
                    }
                }
            }
            val deployedJarFileNames = deployContractJarsV1Request.jarFiles.map {
                val jarFileInputStream = decoder.decode(it.contentBase64).inputStream()
                jcl.add(jarFileInputStream)
                logger.info("Added jar to classpath of Corda Connector Plugin Server: ${it.filename}")
                it.filename
            }

            return DeployContractJarsSuccessV1Response(deployedJarFileNames)
        } catch (ex: Throwable) {
            logger.error("Failed to serve DeployContractJarsV1Request", ex)
            throw ex
        }
    }

    override fun diagnoseNodeV1(diagnoseNodeV1Request: DiagnoseNodeV1Request?): DiagnoseNodeV1Response {
        val reader = mapper.readerFor(object : TypeReference<NodeDiagnosticInfo?>() {})

        val nodeDiagnosticInfoCorda = rpc.proxy.nodeDiagnosticInfo()

        val json = writer.writeValueAsString(nodeDiagnosticInfoCorda)
        logger.debug("NodeDiagnosticInfo JSON=\n{}", json)

        val nodeDiagnosticInfoCactus = reader.readValue<NodeDiagnosticInfo>(json)
        logger.debug("Responding with marshalled ${NodeDiagnosticInfo::class.qualifiedName}: {}", nodeDiagnosticInfoCactus)
        return DiagnoseNodeV1Response(nodeDiagnosticInfo = nodeDiagnosticInfoCactus)
    }

    override fun invokeContractV1(invokeContractV1Request: InvokeContractV1Request?): InvokeContractV1Response {
        Objects.requireNonNull(invokeContractV1Request, "InvokeContractV1Request must be non-null!")
        return dynamicInvoke(rpc.proxy, invokeContractV1Request!!)
    }

    override fun listFlowsV1(listFlowsV1Request: ListFlowsV1Request?): ListFlowsV1Response {
        val flows = rpc.proxy.registeredFlows()
        return ListFlowsV1Response(flows)
    }

    override fun networkMapV1(body: Any?): List<NodeInfo> {
        val reader = mapper.readerFor(object : TypeReference<List<NodeInfo?>?>() {})

        val networkMapSnapshot = rpc.proxy.networkMapSnapshot()
        val networkMapJson = writer.writeValueAsString(networkMapSnapshot)
        logger.trace("networkMapSnapshot=\n{}", networkMapJson)

        val nodeInfoList = reader.readValue<List<NodeInfo>>(networkMapJson)
        logger.info("Returning {} NodeInfo elements in response.", nodeInfoList.size)
        return nodeInfoList
    }
}
