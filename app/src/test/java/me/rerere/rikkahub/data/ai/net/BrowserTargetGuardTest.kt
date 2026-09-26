package me.rerere.rikkahub.data.ai.net

import okhttp3.Dns
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class BrowserTargetGuardTest {
    private fun dns(vararg ips: String) = Dns { _ -> ips.map { InetAddress.getByName(it) } }
    private val failingDns = Dns { host -> throw UnknownHostException("no such host: $host") }

    @Test fun `loopback, lan and metadata literals are refused without dns`() {
        listOf(
            "http://127.0.0.1:8080/api/files",
            "http://192.168.1.1/",
            "http://10.0.0.5/admin",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]:8080/",
            "http://[fd00::1]/",
            "http://0.0.0.0/",
            "http://localhost:3000/",
            "http://LOCALHOST./",
            "http://app.localhost/",
        ).forEach { assertNotNull(it, browserTargetBlockReason(it, failingDns)) }
    }

    @Test fun `names resolving to private addresses are refused`() {
        assertNotNull(browserTargetBlockReason("http://router.lan/", dns("192.168.0.1")))
        assertNotNull(browserTargetBlockReason("https://rebind.example/", dns("93.184.216.34", "127.0.0.1")))
        // java.net.URI returns a null host for underscores; the WebView still resolves it.
        assertNotNull(browserTargetBlockReason("http://my_router:8080/x", dns("192.168.1.1")))
        assertNotNull(browserTargetBlockReason("http://user@my_nas/", dns("10.0.0.2")))
    }

    @Test fun `public targets and dns failures pass`() {
        assertNull(browserTargetBlockReason("https://example.com/?q=1", dns("93.184.216.34")))
        assertNull(browserTargetBlockReason("https://nx.example/", failingDns))
        assertNull(browserTargetBlockReason("about:blank", failingDns))
    }
}
