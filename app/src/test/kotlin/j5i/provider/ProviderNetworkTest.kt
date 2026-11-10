package j5i.spatial8d.provider

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails

class ProviderNetworkTest {
    @Test fun addressPolicyRejectsPrivateAndReservedNetworks() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.2.1", "192.168.1.1", "100.64.1.1", "169.254.169.254", "192.0.2.1", "198.18.0.1", "::1", "fc00::1", "fe80::1", "2001:db8::1")
            .forEach { assertFalse(ProviderNetwork.publicAddress(InetAddress.getByName(it)), it) }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")
            .forEach { assertTrue(ProviderNetwork.publicAddress(InetAddress.getByName(it)), it) }
    }

    @Test fun rejectsLocalTargetsBeforeConnecting() {
        listOf("http://127.0.0.1/", "http://169.254.169.254/", "http://[::1]/")
            .forEach { url -> assertFails { ProviderNetwork.get(url, true) } }
        assertFails { ProviderNetwork.get("http://8.8.8.8/", false) }
    }
}
