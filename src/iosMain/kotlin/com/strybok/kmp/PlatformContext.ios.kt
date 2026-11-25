package com.strybok.kmp

/** iOS needs no context: nothing can create one, and the argument stays `null`. */
public actual abstract class PlatformContext private constructor()
