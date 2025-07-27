package org.example

import kotlinx.coroutines.*
import org.apache.commons.lang3.exception.ExceptionUtils

object MyCoroutines {
    private suspend fun delay(name: String, timeMillis: Long, indent: Int) {
        log("$name delaying $timeMillis ms", indent)
        delay(timeMillis)
    }

    fun CoroutineScope.runSimpleCoroutine(indent: Int) {
        val name = "<SimpleCoroutine>";

        try {
            log("$name start", indent + 1)

            launch {
                try {
                    log("$name launch start", indent + 2)
                    delay(name, 2000, indent + 2)
                    log("$name launch end", indent + 2)
                } catch (e: Throwable) {
                    log("$name launch catch", indent + 2, e)
                    throw e
                }
            }

            log("$name end", indent + 1)
        } catch (e: Throwable) {
            log("$name catch", indent + 1, e)
            throw e
        }
    }

    fun CoroutineScope.runDelayAndThrowCoroutine(indent: Int, exceptionCreator: (String) -> Exception) {
        val name = "<DelayAndThrowCoroutine>";

        try {
            log("$name start", indent + 1)

            launch {
                try {
                    log("$name launch start", indent + 2)
                    delay(name, 2000, indent + 2)
                    log("$name launch throwing", indent + 2)
                    throw exceptionCreator("$name !!exception!!")
                } catch (e: Throwable) {
                    log("$name launch catch", indent + 2, e)
                    throw e
                }
            }

            log("$name end", indent + 1)
        } catch (e: Throwable) {
            log("$name catch", indent + 1, e)
            throw e
        }
    }

    suspend fun CoroutineScope.runAsyncComputeOfValue(indent: Int) {
        val name = "<AsyncComputeOfValue>";

        try {
            log("$name start", indent + 1)

            val r = try {
                async {
                    val value = 42
                    log("$name async-computing value=$value", indent + 2)
                    delay(name, 2000, indent + 2)
                    value
                }
            } catch (e: Throwable) {
                log("$name async-computing catch", indent + 2, e)
                throw e
            }

            try {
                log("$name r-await begin", indent + 2)
                val value = r.await()
                log("$name r-await.result=$value", indent + 2)
                log("$name r-await end", indent + 2)
            } catch (e: Throwable) {
                log("$name r-await catch", indent + 2, e)
                throw e
            }

            log("$name end", indent + 1)
        } catch (e: Throwable) {
            log("$name catch", indent + 1, e)
            throw e
        }
    }


    suspend fun CoroutineScope.runAsyncComputeOfValueButThrowError(indent: Int, exceptionCreator: (String) -> Exception) {
        val name = "<AsyncComputeOfValueButThrowError>";

        try {
            log("$name start", indent + 1)

            val r: Deferred<Int> = try {
                async {
                    delay(name, 2000, indent + 2)
                    throw exceptionCreator("$name !!error!!")
                }
            } catch (e: Throwable) {
                log("$name async-computing catch", indent + 2, e)
                throw e
            }

            try {
                log("$name r-await begin", indent + 2)
                val value = r.await()
                log("$name r-await.result=$value", indent + 2)
                log("$name r-await end", indent + 2)
            } catch (e: Throwable) {
                log("$name r-await catch", indent + 2, e)
                throw e
            }

            log("$name end", indent + 1)
        } catch (e: Throwable) {
            log("$name catch", indent + 1, e)
            throw e
        }

    }

    suspend fun CoroutineScope.runAsyncComputeAndJobCancellation(indent: Int) {
        val name = "<AsyncComputeAndJobCancellation>";

        try {
            log("$name start", indent + 1)

            val rA = try {
                async {
                    log("$name async-computing-A", indent + 2)
                    delay(name, 2000, indent + 2)
                    42
                }
            } catch (e: Throwable) {
                log("$name async-computing-A catch", indent + 2, e)
                throw e
            }

            launch {
                log("$name cancel-job-A", indent + 2)
                delay(name, 500, indent + 2)
                rA.cancel()
                log("$name cancel-job-A DONE", indent + 2)
            }

            val rB = async {
                log("$name async-computing-B (for a long time)", indent + 2)
                try {
                    delay(name, 200_000, indent + 2)
                } catch (e: Throwable) {
                    log("$name async-computing-B catch", indent + 2, e)
                    throw e
                }
                log("$name async-computing-B end", indent + 2)
                666
            }

            awaitForResult(rA, name, "A", indent + 2)
            awaitForResult(rB, name, "B", indent + 2)

            log("$name end", indent + 1)
        } catch (e: Throwable) {
            log("$name catch", indent + 1, e)
            throw e
        }
    }

    fun CoroutineScope.runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(indent: Int, exceptionCreator: (String) -> Exception) {
        val name = "<CoroutineWithIrrelevantException>";

        try {
            log("$name start", indent + 1)

            try {
                launch {
                    log("$name launch-first", indent + 2)
                    delay(name, 1000, indent + 2)
                    throw exceptionCreator("FIRST")
                }
                log("$name launch-first out", indent + 2)
            } catch (e: Throwable) {
                log("$name 3 launch-first catch", indent + 2, e)
                throw e
            }

            fun doLaunch(id: String) {
                launch {
                    log("$name $id start", indent + 2)
                    try {
                        delay(name, 20_000, indent + 2)
                    } catch (e: Throwable) {
                        log("$name $id catch", indent + 2, e)
                        throw e
                    }
                    log("$name $id end", indent + 2)
                }
            }

            doLaunch("AAA-launch")
            doLaunch("BBB-launch")

            log("$name end", indent + 1)
        } catch (e: Throwable) {
            log("$name catch", indent + 1, e)
            throw e
        }
    }

    fun CoroutineScope.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(indent: Int, exceptionCreator: (String) -> Exception) {
        val name = "<DoubleThrowsCoroutineWithIrrelevantException>";

        try {
            log("$name start", indent + 1)
            fun doLaunchInIOAndThrow(id: String) {
                try {
                    launch(Dispatchers.IO) {
                        log("$name $id computing", indent + 2)
                        delay(name, 2000, indent + 2)
                        throw exceptionCreator("FOO-$id-BAR")
                    }
                    log("$name computing out", indent + 2)

                } catch (e: Throwable) {
                    log("$name $id catch", indent + 2, e)
                    throw e
                }
            }
            doLaunchInIOAndThrow("ex-AAA")
            doLaunchInIOAndThrow("ex-BBB")

            fun doLaunchAndDelay(id: String) {
                launch {
                    log("$name $id start", indent + 2)
                    try {
                        delay(name, 20_000, indent + 2)
                    } catch (e: Throwable) {
                        log("$name $id catch", indent + 2, e)
                        throw e
                    }
                    log("$name $id end", indent + 2)
                }
            }
            doLaunchAndDelay("wait-FIRST")
            doLaunchAndDelay("wait-SECOND")
            log("$name end", indent + 1)
        } catch (e: Throwable) {
            log("$name catch", indent + 1, e)
            throw e
        }
    }

    suspend fun awaitForResult(r: Deferred<Int>, name: String, variant: String, indent: Int) {
        try {
            r.await().also {
                log("$name $variant result=$it", indent)
            }
        } catch (e: Throwable) {
            log("$name await $variant catch", indent, e)
            throw e
        }
    }
}

fun main() {


    try {
        log("MAIN begin", 0)

        runBlocking {
            try {
                log("runBlocking begin", 1)

                coroutineScope {
                    try {
                        log("coroutineScope begin", 2)

//                        runSimpleCoroutine(3)
//                        runDelayAndThrowCoroutine(3) {
//                            MyException(it)
//                        }
//                        runDelayAndThrowCoroutine(3) {
//                            MyExoticException(it, RandomUtils.secure().randomInt())
//                        }
//                        runAsyncComputeOfValue(3)
//                        runAsyncComputeOfValueButThrowError(3) {
//                            MyException(it)
//                        }
//                        runAsyncComputeOfValueButThrowError(3) {
//                            MyExoticException(it, RandomUtils.secure().randomInt())
//                        }
//                        runAsyncComputeAndJobCancellation(3)
//                        runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(3) {
//                            MyException(it)
//                        }
//                        runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(3) {
//                            MyExoticException(it, RandomUtils.secure().randomInt())
//                        }
//                        runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(3) {
//                            MyException(it)
//                        }
//                        runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(3) {
//                            MyExoticException(it, RandomUtils.secure().randomInt())
//                        }

                        log("coroutineScope end", 2)
                    } catch (e: Throwable) {
                        log("coroutineScope catch", 2, e)
                        throw e
                    }
                }

                log("runBlocking end", 1)
            } catch (e: Throwable) {
                log("runBlocking catch", 1, e)
                throw e
            }
        }

        log("MAIN end", 0)
    } catch (e: Throwable) {
        log("MAIN catch", 0, e)
        e.printStackTrace(System.err)
    }
}

fun log(msg: String, indent: Int, prefix: String = "") {
    println("${" ".repeat(indent)}$prefix${Thread.currentThread().name}: $msg")
}

fun log(msg: String, indent: Int, e: Throwable) {
    val rootCause = if (e.cause == null) null else ExceptionUtils.getRootCause(e)

    fun exceptionToString(e: Throwable): String {
        val suppressedCount = e.suppressed?.size ?: 0
        return "e.toString='$e', suppressedCount=$suppressedCount"
    }

    val line1 = "${" ".repeat(indent)}${Thread.currentThread().name}: $msg ${exceptionToString(e)}"
    val line2 = if (rootCause == null) null else "${" ".repeat(indent + 2)} rootCause=${exceptionToString(rootCause)}"
    println(line1 + if (line2.isNullOrEmpty()) "" else "\n" + line2)
}

@OptIn(ExperimentalStdlibApi::class)
private fun logExceptionCreation(exception: Exception, parametersStr: String) {
    val recover = Thread.currentThread().stackTrace.any { it.methodName.contains("recover") }.let { isRecovered ->
        if (isRecovered) " [recoverStackTrace -> duplicate exception]" else " [original]"
    }

    log(exception.javaClass.simpleName + ".<ctor>(" + parametersStr + ")@" + exception.hashCode().toHexString() + recover, 20, " *** ")
}

@OptIn(ExperimentalStdlibApi::class)
class MyException(message: String) : IllegalStateException(message) {
    init {
        logExceptionCreation(this, "'$message'")
    }

    override fun getLocalizedMessage(): String? {
        return super.getLocalizedMessage() + "@" + hashCode().toHexString()
    }
}

@OptIn(ExperimentalStdlibApi::class)
class MyExoticException(message: String, value: Int) : IllegalStateException(message) {
    init {
        logExceptionCreation(this, "'$message', $value")
    }

    override fun getLocalizedMessage(): String? {
        return super.getLocalizedMessage() + "@" + hashCode().toHexString()
    }
}