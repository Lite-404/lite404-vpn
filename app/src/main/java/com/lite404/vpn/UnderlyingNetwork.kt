package com.lite404.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler

/** Следим за лучшей сетью с интернетом за пределами VPN. */
internal fun ConnectivityManager.watchUnderlyingNetwork(
    callback: ConnectivityManager.NetworkCallback,
    handler: Handler,
) {
    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    if (Build.VERSION.SDK_INT >= 31) {
        registerBestMatchingNetworkCallback(request, callback, handler)
    } else {
        requestNetwork(request, callback, handler)
    }
}
