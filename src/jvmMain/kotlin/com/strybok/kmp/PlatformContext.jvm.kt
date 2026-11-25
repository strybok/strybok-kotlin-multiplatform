package com.strybok.kmp

/** The JVM has no deferred links: nothing can create a context, and the argument stays `null`. */
public actual abstract class PlatformContext private constructor()
