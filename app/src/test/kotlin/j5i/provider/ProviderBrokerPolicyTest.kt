package j5i.spatial8d.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ProviderBrokerPolicyTest {
    @Test fun allowsOnlyDeclaredDomainAndItsSubdomains() {
        val domains = listOf("*.music.126.net")
        ProviderBrokerPolicy.validateUrl("https://m801.music.126.net/song.mp3", domains)
        kotlin.test.assertFails { ProviderBrokerPolicy.validateUrl("https://music.126.net/song.mp3", domains) }
        kotlin.test.assertFails { ProviderBrokerPolicy.validateUrl("https://music.126.net.evil.example/song.mp3", domains) }
    }
    @Test fun supportsBilibiliDynamicCdnsWithoutAllowingBackupPartners() {
        val domains = listOf("*.bilivideo.com", "*.bilivideo.cn")
        ProviderBrokerPolicy.validateUrl("https://xy223x94x121x138xy.mcdn.bilivideo.cn/audio.m4a", domains)
        ProviderBrokerPolicy.validateUrl("https://cn-fjqz-cm-01-01.bilivideo.com/audio.m4a", domains)
        ProviderBrokerPolicy.validateUrl("https://xy116x196x140x25xy.mcdn.bilivideo.cn:8082/audio.m4a", domains, allowHttp = true)
        assertFails { ProviderBrokerPolicy.validateUrl("https://b-baacc0qo05dltm107bs0ymuc1a.edge.mountaintoys.cn/audio.m4a", domains) }
    }
    @Test fun confinesRequestsToDeclaredDomains() {
        val domains = listOf("example.com")
        val request = ProviderBrokerPolicy.parse("""{"method":"POST","url":"https://example.com/api","body":"hello"}""", domains)
        assertEquals("POST", request.method)
        assertFails { ProviderBrokerPolicy.validateUrl("https://example.com.attacker.test/api", domains) }
        assertFails { ProviderBrokerPolicy.validateUrl("https://127.0.0.1/", domains) }
        ProviderBrokerPolicy.validateUrl("http://example.com/", domains)
        ProviderBrokerPolicy.parse("""{"method":"GET","url":"http://example.com:8080/api"}""", domains)
        ProviderBrokerPolicy.validateUrl("http://example.com/song.mp3", domains, allowHttp = true)
        ProviderBrokerPolicy.validateUrl("http://example.com:8080/song.mp3", domains, allowHttp = true)
        assertFails { ProviderBrokerPolicy.validateUrl("http://example.com:65536/song.mp3", domains, allowHttp = true) }
        assertFails { ProviderBrokerPolicy.validateUrl("http://example.com.attacker.test/song.mp3", domains, allowHttp = true) }
        assertFails { ProviderBrokerPolicy.validateUrl("https://user@example.com/", domains) }
        assertFails { ProviderBrokerPolicy.parse("""{"method":"GET","url":"https://example.com/","headers":{"Authorization":"secret"}}""", domains) }
        assertFails { ProviderBrokerPolicy.parse("""{"method":"GET","url":"https://example.com/","headers":{"Referer":"https://attacker.test/"}}""", domains) }
        ProviderBrokerPolicy.parse("""{"method":"POST","url":"https://example.com/api","headers":{"Origin":"https://example.com"},"body":"{}"}""", domains)
    }

    @Test fun credentialsStayOnInitialHost() {
        kotlin.test.assertTrue(ProviderNetwork.forwardHeader("Cookie", "example.com", "EXAMPLE.com"))
        kotlin.test.assertFalse(ProviderNetwork.forwardHeader("Cookie", "example.com", "other.example.com"))
        kotlin.test.assertTrue(ProviderNetwork.forwardHeader("Referer", "example.com", "other.example.com"))
    }
}
