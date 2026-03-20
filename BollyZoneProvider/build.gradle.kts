// BollyZoneProvider module build config
dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}


// Use an integer for version numbers
version = 2

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Watch Indian TV Shows and Hindi Dramas from BollyZone"
    authors = listOf("BuiltBySid","Sid")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Movie")

    requiresResources = true
    // Language of the content
    language = "hi" // Hindi

    // Random CC logo I found
    iconUrl = "https://www.bollyzone.to/wp-content/uploads/2021/10/bollyzone-logo.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}