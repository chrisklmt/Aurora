package gr.hua.aurora.transport.hybrid

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapCommandExecutorFactoryTest {
    @Test
    fun factoryCreateWithDefaultConfigReturnsARejectingNoOpExecutor() {
        val executor = HybridBootstrapCommandExecutorFactory.create()

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap execution is disabled."
            ),
            result
        )
    }

    @Test
    fun factoryCreateWithCustomNoOpConfigPreservesCustomRejectionReason() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.NO_OP,
                noOpRejectionReason = "Factory custom rejection."
            )
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Factory custom rejection."
            ),
            result
        )
    }

    @Test
    fun factoryNoOpReturnsARejectingExecutor() {
        val executor = HybridBootstrapCommandExecutorFactory.noOp()

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap execution is disabled."
            ),
            result
        )
    }

    @Test
    fun factoryDefaultRuntimeExecutorDelegatesToDefaultConfigBehavior() {
        val factoryExecutor = HybridBootstrapCommandExecutorFactory.create()
        val runtimeExecutor = HybridBootstrapCommandExecutorFactory.defaultRuntimeExecutor()
        val command = command()

        val factoryResult = factoryExecutor.execute(command)
        val runtimeResult = runtimeExecutor.execute(command.copy())

        assertEquals(factoryResult, runtimeResult)
    }

    @Test
    fun factoryDefaultRuntimeExecutorDoesNotReturnFakeExecutor() {
        val executor = HybridBootstrapCommandExecutorFactory.defaultRuntimeExecutor()

        assertFalse(executor is FakeHybridBootstrapCommandExecutor)
        assertTrue(executor is NoOpHybridBootstrapCommandExecutor)
    }

    @Test
    fun factoryConstructionIsPassiveAndDoesNotExposeExecutionHistory() {
        val executor = HybridBootstrapCommandExecutorFactory.create()
        val methodNames = executor::class.java.methods.map { it.name }

        assertFalse(methodNames.contains("getExecutedCommands"))
        assertFalse(methodNames.contains("getTriggerHistory"))
        assertFalse(methodNames.contains("getLatestResult"))
    }

    @Test
    fun socketPlanDisabledConfigReturnsRejectedDisabledSocketReasonForValidCommand() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap socket connector is disabled."
            ),
            result
        )
    }

    @Test
    fun socketPlanDisabledConfigPreservesCustomDisabledSocketFailureReason() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED,
                disabledSocketConnectorFailureReason = "Factory disabled socket rejection."
            )
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Factory disabled socket rejection."
            ),
            result
        )
    }

    @Test
    fun socketPlanDisabledDoesNotReturnFakeExecutor() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        assertFalse(executor is FakeHybridBootstrapCommandExecutor)
    }

    @Test
    fun socketPlanDisabledDoesNotReturnNoOpBehaviorReason() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        val result = executor.execute(command())

        assertFalse(
            result == HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap execution is disabled."
            )
        )
    }

    @Test
    fun socketPlanDisabledUsesSocketPlanExecutorType() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        assertTrue(executor is HybridBootstrapSocketPlanCommandExecutor)
    }

    @Test
    fun socketPlanDisabledUsesConnectorFactoryDisabledBehaviorIndirectly() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapCommandExecutorFactory.kt"
        )

        assertTrue(source.contains("HybridBootstrapSocketConnectorFactory.disabled("))
    }

    @Test
    fun factoryDoesNotCallConnectorConnectDuringConstruction() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        assertTrue(executor is HybridBootstrapSocketPlanCommandExecutor)
    }

    @Test
    fun factoryDoesNotCallDialerDialDuringConstruction() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        assertTrue(executor is HybridBootstrapSocketPlanCommandExecutor)
    }

    @Test
    fun factoryDoesNotImportJavaNetSocket() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapCommandExecutorFactory.kt"
        )

        assertFalse(source.contains("import java.net.Socket"))
    }

    @Test
    fun socketImportRemainsIsolatedToJavaNetHybridBootstrapSocketDialer() {
        val executorFactorySource = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapCommandExecutorFactory.kt"
        )
        val javaNetSource = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/JavaNetHybridBootstrapSocketDialer.kt"
        )

        assertFalse(executorFactorySource.contains("import java.net.Socket"))
        assertTrue(javaNetSource.contains("import java.net.Socket"))
    }

    @Test
    fun noServerSocketAppearsInChangedFactoryFiles() {
        changedMainSourcePaths().forEach { path ->
            assertFalse(sourceText(path).contains("ServerSocket"))
        }
    }

    @Test
    fun noWifiP2pApisAppearInChangedFactoryFiles() {
        changedMainSourcePaths().forEach { path ->
            val source = sourceText(path)

            assertFalse(source.contains("WifiP2pManager"))
            assertFalse(source.contains("WifiP2pConfig"))
        }
    }

    @Test
    fun socketPlanDisabledFactoryDoesNotCallJavaNetConnectorFactory() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapCommandExecutorFactory.kt"
        )

        assertFalse(source.contains("HybridBootstrapSocketConnectorFactory.javaNet("))
    }

    private fun command(
        peerId: String = "peer-factory",
        sessionId: String = "session-factory",
        bootstrapIdentifier: String = "bootstrap-factory",
        groupOwnerAddress: String = "192.168.49.181",
        socketPort: Int = 9181,
        latestCreatedAtMillis: Long = 1_734_000_000L,
        requestedAtMillis: Long = 1_734_000_001L,
        commandCreatedAtMillis: Long = 1_734_000_002L
    ): HybridBootstrapAttemptCommand {
        return HybridBootstrapAttemptCommand(
            peerId = peerId,
            sessionId = sessionId,
            bootstrapIdentifier = bootstrapIdentifier,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            latestCreatedAtMillis = latestCreatedAtMillis,
            requestedAtMillis = requestedAtMillis,
            commandCreatedAtMillis = commandCreatedAtMillis
        )
    }

    private fun changedMainSourcePaths(): List<String> {
        return listOf(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapCommandExecutorFactory.kt"
        )
    }

    private fun sourceText(relativePath: String): String {
        val sourcePath = resolveSourcePath(relativePath)
        return String(
            Files.readAllBytes(sourcePath),
            UTF_8
        )
    }

    private fun resolveSourcePath(relativePath: String): Path {
        val direct = Path.of(relativePath)
        if (Files.exists(direct)) {
            return direct
        }

        val parent = Path.of("..").resolve(relativePath).normalize()
        if (Files.exists(parent)) {
            return parent
        }

        val grandParent = Path.of("..", "..").resolve(relativePath).normalize()
        if (Files.exists(grandParent)) {
            return grandParent
        }

        error(
            "Missing source file: $relativePath (user.dir=${System.getProperty("user.dir")})"
        )
    }
}
