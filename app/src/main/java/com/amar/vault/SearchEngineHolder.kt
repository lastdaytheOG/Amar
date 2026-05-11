package com.amar.vault

object SearchEngineHolder {
    val engine: NativeSearchEngine by lazy {
        NativeSearchEngine().also { it.initEngine() }
    }
}