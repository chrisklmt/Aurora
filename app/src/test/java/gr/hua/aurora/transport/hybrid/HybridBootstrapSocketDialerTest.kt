package gr.hua.aurora.transport.hybrid

import gr.hua.aurora.state.currentHybridBootstrapCommandExecutorConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapSocketDialerTest {
    @Test
    fun fakeDialerReturnsConnected() {
        val result = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_740_000_001L
        ).dial(
            address = "192.168.49.10",
            port = 8_988,
            connectTimeoutMillis = 5_000L
        )

        assertTrue(result is HybridBootstrapSocketDialResult.Connected)
    }

    @Test
    fun fakeConnectedPreservesAddress() {
        val result = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_740_000_002L
        ).dial(
            address = "fe80::abcd",
            port = 8_989,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            "fe80::abcd",
            (result as HybridBootstrapSocketDialResult.Connected).address
        )
    }

    @Test
    fun fakeConnectedPreservesPort() {
        val result = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_740_000_003L
        ).dial(
            address = "192.168.49.12",
            port = 65_535,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            65_535,
            (result as HybridBootstrapSocketDialResult.Connected).port
        )
    }

    @Test
    fun fakeConnectedPreservesConnectedAtMillis() {
        val result = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_740_000_004L
        ).dial(
            address = "192.168.49.13",
            port = 9_013,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            1_740_000_004L,
            (result as HybridBootstrapSocketDialResult.Connected).connectedAtMillis
        )
    }

    @Test
    fun fakeDialerReturnsFailedWithExactReason() {
        val result = FakeHybridBootstrapSocketDialer.failed(
            reason = "Fake dialer rejection."
        ).dial(
            address = "192.168.49.14",
            port = 9_014,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Fake dialer rejection."
            ),
            result
        )
    }

    @Test
    fun fakeDialerRecordsOneRequest() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_740_000_005L
        )

        dialer.dial(
            address = "192.168.49.15",
            port = 9_015,
            connectTimeoutMillis = 4_000L
        )

        assertEquals(
            listOf(
                FakeHybridBootstrapSocketDialer.DialRequest(
                    address = "192.168.49.15",
                    port = 9_015,
                    connectTimeoutMillis = 4_000L
                )
            ),
            dialer.dialRequests
        )
    }

    @Test
    fun fakeDialerRecordsMultipleRequestsInOrder() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_740_000_006L
        )

        dialer.dial(
            address = "192.168.49.16",
            port = 9_016,
            connectTimeoutMillis = 4_001L
        )
        dialer.dial(
            address = "192.168.49.17",
            port = 9_017,
            connectTimeoutMillis = 4_002L
        )

        assertEquals(
            listOf(
                FakeHybridBootstrapSocketDialer.DialRequest(
                    address = "192.168.49.16",
                    port = 9_016,
                    connectTimeoutMillis = 4_001L
                ),
                FakeHybridBootstrapSocketDialer.DialRequest(
                    address = "192.168.49.17",
                    port = 9_017,
                    connectTimeoutMillis = 4_002L
                )
            ),
            dialer.dialRequests
        )
    }

    @Test
    fun fakeDialerExposesDefensiveCopyHistory() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_740_000_007L
        )
        dialer.dial(
            address = "192.168.49.18",
            port = 9_018,
            connectTimeoutMillis = 4_003L
        )

        val firstRead = dialer.dialRequests.toMutableList()
        firstRead += FakeHybridBootstrapSocketDialer.DialRequest(
            address = "mutated",
            port = 1,
            connectTimeoutMillis = 1L
        )

        assertEquals(1, dialer.dialRequests.size)
        assertEquals("192.168.49.18", dialer.dialRequests.single().address)
    }

    @Test
    fun fakeDialerDoesNotUseSocketImport() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/FakeHybridBootstrapSocketDialer.kt"
        )

        assertFalse(source.contains("import java.net.Socket"))
    }

    @Test
    fun javaNetDialerRejectsBlankAddressWithoutCreatingSocket() {
        var socketFactoryCalls = 0
        val dialer = JavaNetHybridBootstrapSocketDialer(
            socketFactory = {
                socketFactoryCalls += 1
                error("socketFactory must not be called for blank address")
            }
        )

        val result = dialer.dial(
            address = "   ",
            port = 9_019,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial address must not be blank."
            ),
            result
        )
        assertEquals(0, socketFactoryCalls)
    }

    @Test
    fun javaNetDialerRejectsPortZeroWithoutCreatingSocket() {
        var socketFactoryCalls = 0
        val dialer = JavaNetHybridBootstrapSocketDialer(
            socketFactory = {
                socketFactoryCalls += 1
                error("socketFactory must not be called for invalid port")
            }
        )

        val result = dialer.dial(
            address = "192.168.49.20",
            port = 0,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial port must be in 1..65535."
            ),
            result
        )
        assertEquals(0, socketFactoryCalls)
    }

    @Test
    fun javaNetDialerRejectsPortAboveRangeWithoutCreatingSocket() {
        var socketFactoryCalls = 0
        val dialer = JavaNetHybridBootstrapSocketDialer(
            socketFactory = {
                socketFactoryCalls += 1
                error("socketFactory must not be called for invalid port")
            }
        )

        val result = dialer.dial(
            address = "192.168.49.21",
            port = 65_536,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial port must be in 1..65535."
            ),
            result
        )
        assertEquals(0, socketFactoryCalls)
    }

    @Test
    fun javaNetDialerRejectsZeroTimeoutWithoutCreatingSocket() {
        var socketFactoryCalls = 0
        val dialer = JavaNetHybridBootstrapSocketDialer(
            socketFactory = {
                socketFactoryCalls += 1
                error("socketFactory must not be called for invalid timeout")
            }
        )

        val result = dialer.dial(
            address = "192.168.49.22",
            port = 9_022,
            connectTimeoutMillis = 0L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial connectTimeoutMillis must be in 1..30000."
            ),
            result
        )
        assertEquals(0, socketFactoryCalls)
    }

    @Test
    fun javaNetDialerRejectsNegativeTimeoutWithoutCreatingSocket() {
        var socketFactoryCalls = 0
        val dialer = JavaNetHybridBootstrapSocketDialer(
            socketFactory = {
                socketFactoryCalls += 1
                error("socketFactory must not be called for invalid timeout")
            }
        )

        val result = dialer.dial(
            address = "192.168.49.23",
            port = 9_023,
            connectTimeoutMillis = -1L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial connectTimeoutMillis must be in 1..30000."
            ),
            result
        )
        assertEquals(0, socketFactoryCalls)
    }

    @Test
    fun javaNetDialerRejectsTimeoutAboveMaximumWithoutCreatingSocket() {
        var socketFactoryCalls = 0
        val dialer = JavaNetHybridBootstrapSocketDialer(
            socketFactory = {
                socketFactoryCalls += 1
                error("socketFactory must not be called for invalid timeout")
            }
        )

        val result = dialer.dial(
            address = "192.168.49.24",
            port = 9_024,
            connectTimeoutMillis = 30_001L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial connectTimeoutMillis must be in 1..30000."
            ),
            result
        )
        assertEquals(0, socketFactoryCalls)
    }

    @Test
    fun javaNetDialerConvertsSafeTimeoutWithoutOverflowRisk() {
        assertEquals(
            30_000,
            requireNotNull(
                hybridBootstrapSocketDialConnectTimeoutMillisOrNull(30_000L)
            )
        )
        assertNull(hybridBootstrapSocketDialConnectTimeoutMillisOrNull(Long.MAX_VALUE))
    }

    @Test
    fun javaNetDialerReturnsSafeFailureForIoException() {
        val dialer = JavaNetHybridBootstrapSocketDialer(
            socketFactory = {
                throw IOException("synthetic failure")
            }
        )

        val result = dialer.dial(
            address = "192.168.49.25",
            port = 9_025,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial failed: I/O error."
            ),
            result
        )
    }

    @Test
    fun javaNetDialerReturnsSafeFailureForSecurityException() {
        val dialer = JavaNetHybridBootstrapSocketDialer(
            socketFactory = {
                throw SecurityException("blocked")
            }
        )

        val result = dialer.dial(
            address = "192.168.49.26",
            port = 9_026,
            connectTimeoutMillis = 5_000L
        )

        assertEquals(
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial failed: security error."
            ),
            result
        )
    }

    @Test
    fun socketImportAppearsOnlyInJavaNetDialerNewFile() {
        val newMainSources = newMainSourcePaths()
            .associateWith(::sourceText)

        val socketImportPaths = newMainSources
            .filterValues { it.contains("import java.net.Socket") }
            .keys
            .toList()

        assertEquals(
            listOf(
                "app/src/main/java/gr/hua/aurora/transport/hybrid/JavaNetHybridBootstrapSocketDialer.kt"
            ),
            socketImportPaths
        )
    }

    @Test
    fun newDialerFilesDoNotMentionServerSocketOrWifiDirectAndroidApis() {
        newMainSourcePaths().forEach { path ->
            val source = sourceText(path)

            assertFalse(source.contains("ServerSocket"))
            assertFalse(source.contains("WifiP2pManager"))
            assertFalse(source.contains("WifiP2pConfig"))
        }
    }

    @Test
    fun newDialerFilesDoNotReferenceBleOrWifiDirectFrames() {
        newMainSourcePaths().forEach { path ->
            val source = sourceText(path)

            assertFalse(source.contains("BleGattTransportFrame"))
            assertFalse(source.contains("WifiDirectTransportFrame"))
        }
    }

    @Test
    fun auroraBleRuntimeHostAndFactoryAndUiDoNotReferenceNewDialers() {
        val runtimeSource = sourceText(
            "app/src/main/java/gr/hua/aurora/state/AuroraBleRuntimeHost.kt"
        )
        val factorySource = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapCommandExecutorFactory.kt"
        )
        val nearbySource = sourceText(
            "app/src/main/java/gr/hua/aurora/ui/screens/NearbyDevicesScreen.kt"
        )

        listOf(runtimeSource, factorySource, nearbySource).forEach { source ->
            assertFalse(source.contains("HybridBootstrapSocketDialer"))
            assertFalse(source.contains("JavaNetHybridBootstrapSocketDialer"))
            assertFalse(source.contains("FakeHybridBootstrapSocketDialer"))
        }
    }

    @Test
    fun runtimeStillUsesSocketPlanDisabledWithDisabledConnectorBehavior() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            currentHybridBootstrapCommandExecutorConfig()
        )

        val result = executor.execute(validCommand())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap socket connector is disabled."
            ),
            result
        )
    }

    private fun validCommand(): HybridBootstrapAttemptCommand {
        return HybridBootstrapAttemptCommand(
            peerId = "peer-runtime",
            sessionId = "session-runtime",
            bootstrapIdentifier = "bootstrap-runtime",
            groupOwnerAddress = "192.168.49.27",
            socketPort = 9_027,
            latestCreatedAtMillis = 1_740_000_100L,
            requestedAtMillis = 1_740_000_101L,
            commandCreatedAtMillis = 1_740_000_102L
        )
    }

    private fun newMainSourcePaths(): List<String> {
        return listOf(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapSocketDialResult.kt",
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapSocketDialer.kt",
            "app/src/main/java/gr/hua/aurora/transport/hybrid/FakeHybridBootstrapSocketDialer.kt",
            "app/src/main/java/gr/hua/aurora/transport/hybrid/JavaNetHybridBootstrapSocketDialer.kt"
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
