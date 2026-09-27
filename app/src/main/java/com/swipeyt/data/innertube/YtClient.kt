package com.swipeyt.data.innertube

/**
 * InnerTube "client" identities. YouTube Music's private API behaves differently per client:
 * WEB_REMIX is the real web app (needed for account/library operations), while ANDROID_VR and
 * IOS return stream URLs that are neither ciphered nor throttled, so they are used for playback.
 */
data class YtClient(
    val clientName: String,
    val clientId: Int,
    val clientVersion: String,
    val userAgent: String,
    val apiKey: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: Int? = null,
    /** Whether login cookies should be attached to requests for this client. */
    val supportsLogin: Boolean = false,
) {
    companion object {
        val WEB_REMIX = YtClient(
            clientName = "WEB_REMIX",
            clientId = 67,
            clientVersion = "1.20250310.01.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
            supportsLogin = true,
        )

        val ANDROID_VR = YtClient(
            clientName = "ANDROID_VR",
            clientId = 28,
            clientVersion = "1.61.48",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
        )

        val IOS = YtClient(
            clientName = "IOS",
            clientId = 5,
            clientVersion = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
            osName = "iOS",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
        )

        /** Order in which playback clients are tried when resolving a stream. */
        val STREAM_CLIENTS = listOf(ANDROID_VR, IOS, WEB_REMIX)
    }
}
