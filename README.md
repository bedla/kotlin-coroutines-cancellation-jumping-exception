# Kotlin Coroutines internal behavior when exceptions are thrown and/or Jobs are canceled

During discussions with a fellow developer we open a topic about catching `kotlinx.coroutines.JobCancellationException` and getting cause from it.

I was arguing that this exception is internal and is kind of implementation detail that should not be touched. From implementation of it's
`kotlinx.coroutines.JobCancellationException.fillInStackTrace()` method, you can figure out that it is fast-throw kind of exception
(_trick is about not calling `java.lang.Throwable.fillInStackTrace(int)` and keep `java.lang.Throwable.stackTrace` field as empty array -
DO NOT DO THIS, IF YOU DO NOT KNOW WHAT YOU ARE DOING_).
It is usually used when you want to roll back whole deeply nested stack till first `catch` statement, where you will somehow process it.
Similar patter is used when you implement the Interpreter pattern in Java and want to support `break` and `continue` statements in you DSL.

My fellow developer had an idea, that he will catch it and rethrow it's `cause`. Because he was seeing strange behavior, and wanted to duck-tape
an actual problem.

This was an interesting topic to jump into rabbit hole and explore :)

I prepared code you can see in this repository with some use-cases and extensive logging, to be able to see what is happening under the hood.

## Use-case 1: Simple Coroutine, return value

Basic coroutine that delays for time (to mimic heavy processing) and then ends. Nothing unexpected is happening here.

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <SimpleCoroutine> start
    main @coroutine#1: <SimpleCoroutine> end
  main @coroutine#1: coroutineScope end
     main @coroutine#2: <SimpleCoroutine> launch start
     main @coroutine#2: <SimpleCoroutine> delaying 2000 ms
     main @coroutine#2: <SimpleCoroutine> launch end
 main @coroutine#1: runBlocking end
main: MAIN end
```

From the console output above you can see that:

- It started the main code block and coroutineScope, and did not wait `launch` to finish
- It launched coroutine, delayed 2 seconds and just finished
- At the end it was waiting to all coroutines to finish because of `runBlocking` method call

Notice:

- Seems that `coroutineScope` finishes before `launch` coroutine finishes it's work.

## Use-case 2a: Simple Coroutine, delay and throw duplicable exception

Now something interesting is starting to happen. Start of coroutine is the same as we had in the previous sample.
But instead of returning some value, we are throwing exception.

You can see from this line that we are throwing exception, mind `hashCode=5af97850`.

```
     main @coroutine#2: <DelayAndThrowCoroutine> launch throwing
                     *** main @coroutine#2: MyException.<ctor>('<DelayAndThrowCoroutine> !!exception!!')@5af97850 [original]
```

What is happening under the hood is that at some point Coroutines machinery creates **duplicate** exception, mind `hashCode=47d90b9e`.
And adds **original** exception `hashCode=5af97850` as **rootCause** of **duplicate** exception.

Mind that duplicate exception has completely different stack-trace.

Then you can see that we catch **duplicate** in `runBlocking` and `main` try/catch blocks.

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <DelayAndThrowCoroutine> start
    main @coroutine#1: <DelayAndThrowCoroutine> end
  main @coroutine#1: coroutineScope end
     main @coroutine#2: <DelayAndThrowCoroutine> launch start
     main @coroutine#2: <DelayAndThrowCoroutine> delaying 2000 ms
     main @coroutine#2: <DelayAndThrowCoroutine> launch throwing
                     *** main @coroutine#2: MyException.<ctor>('<DelayAndThrowCoroutine> !!exception!!')@5af97850 [original]
     main @coroutine#2: <DelayAndThrowCoroutine> launch catch e.toString='org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@5af97850', suppressedCount=0
                     *** main @coroutine#2: MyException.<ctor>('<DelayAndThrowCoroutine> !!exception!!')@47d90b9e [recoverStackTrace -> duplicate exception]
 main @coroutine#1: runBlocking catch e.toString='org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@47d90b9e', suppressedCount=0
    rootCause=e.toString='org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@5af97850', suppressedCount=0
main: MAIN catch e.toString='org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@47d90b9e', suppressedCount=0
   rootCause=e.toString='org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@5af97850', suppressedCount=0
```

The same could be seen in exception thrown stack trace logged into console with `uncaughtExceptionHandler`.

```
org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@47d90b9e
	at org.example.MyCoroutines$runDelayAndThrowCoroutine$1.invokeSuspend(Main.kt:48)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:283)
Caused by: org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@5af97850
	at org.example.MyCoroutines$runDelayAndThrowCoroutine$1.invokeSuspend(Main.kt:48)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
	at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
	at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
	at org.example.MyCoroutines.runDelayAndThrowCoroutine(Main.kt:43)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:288)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:283)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:279)
	at org.example.MainKt.main(Main.kt)
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that no duplicate exception was created and stack trace is without "_COROUTINE._BOUNDARY" machinery.

```
main: MAIN begin
 main: runBlocking begin
  main: coroutineScope begin
    main: <DelayAndThrowCoroutine> start
    main: <DelayAndThrowCoroutine> end
  main: coroutineScope end
     main: <DelayAndThrowCoroutine> launch start
     main: <DelayAndThrowCoroutine> delaying 2000 ms
     main: <DelayAndThrowCoroutine> launch throwing
                     *** main: MyException.<ctor>('<DelayAndThrowCoroutine> !!exception!!')@55ca8de8 [original]
     main: <DelayAndThrowCoroutine> launch catch e.toString='org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@55ca8de8', suppressedCount=0
 main: runBlocking catch e.toString='org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@55ca8de8', suppressedCount=0
main: MAIN catch e.toString='org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@55ca8de8', suppressedCount=0
---
org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@55ca8de8
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:290)
	at org.example.MyCoroutines$runDelayAndThrowCoroutine$1.invokeSuspend(Main.kt:48)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
```

## Use-case 2b: Simple Coroutine, delay and throw NON-duplicable exception

Interesting stuff happens when Kotlin Coroutines machinery is unable to duplicate the original exception. In this case, only one
exception `hashCode=419c5f1a` is used as you can see from the log.

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <DelayAndThrowCoroutine> start
    main @coroutine#1: <DelayAndThrowCoroutine> end
  main @coroutine#1: coroutineScope end
     main @coroutine#2: <DelayAndThrowCoroutine> launch start
     main @coroutine#2: <DelayAndThrowCoroutine> delaying 2000 ms
     main @coroutine#2: <DelayAndThrowCoroutine> launch throwing
                     *** main @coroutine#2: MyExoticException.<ctor>('<DelayAndThrowCoroutine> !!exception!!', 1425626732)@419c5f1a [original]
     main @coroutine#2: <DelayAndThrowCoroutine> launch catch e.toString='org.example.MyExoticException: <DelayAndThrowCoroutine> !!exception!!@419c5f1a', suppressedCount=0
 main @coroutine#1: runBlocking catch e.toString='org.example.MyExoticException: <DelayAndThrowCoroutine> !!exception!!@419c5f1a', suppressedCount=0
main: MAIN catch e.toString='org.example.MyExoticException: <DelayAndThrowCoroutine> !!exception!!@419c5f1a', suppressedCount=0
```

Stack trace is not modified and is similar to stack trace of `cause` exception from "Use-case 2a".

```
org.example.MyExoticException: <DelayAndThrowCoroutine> !!exception!!@419c5f1a
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:294)
	at org.example.MyCoroutines$runDelayAndThrowCoroutine$1.invokeSuspend(Main.kt:49)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
	at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
	at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
	at org.example.MyCoroutines.runDelayAndThrowCoroutine(Main.kt:44)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:293)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:285)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:281)
	at org.example.MainKt.main(Main.kt)
```

What is missing is **COROUTINE BOUNDARY** from "Use-case 2a".

```
org.example.MyException: <DelayAndThrowCoroutine> !!exception!!@XXXXXX
	at org.example.MyCoroutines$runDelayAndThrowCoroutine$1.invokeSuspend(Main.kt:48)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:283)
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that output is the same as when run without this option. Kotlin coroutines were unable to create duplicate exception.

## Use-case 3: Simple Coroutine, async computation

This example is very similar to Use-case 1, but it computes value using `async` coroutine.
From the console log, nothing shocking is happening.

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <AsyncComputeOfValue> start
     main @coroutine#1: <AsyncComputeOfValue> r-await begin
     main @coroutine#2: <AsyncComputeOfValue> async-computing value=42
     main @coroutine#2: <AsyncComputeOfValue> delaying 2000 ms
     main @coroutine#1: <AsyncComputeOfValue> r-await.result=42
     main @coroutine#1: <AsyncComputeOfValue> r-await end
    main @coroutine#1: <AsyncComputeOfValue> end
  main @coroutine#1: coroutineScope end
 main @coroutine#1: runBlocking end
main: MAIN end
```

1. Async computation is started
2. Flows goes and stops at call to `Deferred.await()` method
3. Async computation takes some time, in our case 2 seconds
4. When it finishes, `await` is unblocked and result value is returned to the caller
5. Then you can see that all blocks are successfully finished

## Use-case 4a: Async computation throwing the error, delay and throw duplicable exception

This use-case is similar to "Use-case 4" but we during computation after delay throw custom exception.

1. In Async coroutine computation is started, delayed, and at the end exception is thrown.
2. In the same time we are waiting at `Deferred.await()` method call.
3. You can see at `r-await catch` log message that `await()` is terminated by the exception.

We can see that the **original** Exception `hashCode=691a7f8f` is created.

Also, this time **two duplicated** exceptions (`hashCode1=5be6e01c` and `hashCode2=7722c3c3`) are created.
This is different to previous use-cases, where only one duplicate exception was created.

**First exception** `hashCode1=5be6e01c` is used to escape `CoroutineScope`.

**Second exception** `hashCode2=7722c3c3` is used to escape `runCatching` method.

Both of them have **original exception** `hashCode=691a7f8f` as `rootCause`.

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <AsyncComputeOfValueButThrowError> start
     main @coroutine#1: <AsyncComputeOfValueButThrowError> r-await begin
     main @coroutine#2: <AsyncComputeOfValueButThrowError> delaying 2000 ms
                     *** main @coroutine#2: MyException.<ctor>('<AsyncComputeOfValueButThrowError> !!error!!')@691a7f8f [original]
                     *** main @coroutine#1: MyException.<ctor>('<AsyncComputeOfValueButThrowError> !!error!!')@5be6e01c [recoverStackTrace -> duplicate exception]
     main @coroutine#1: <AsyncComputeOfValueButThrowError> r-await catch e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@5be6e01c', suppressedCount=0
        rootCause=e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@691a7f8f', suppressedCount=0
    main @coroutine#1: <AsyncComputeOfValueButThrowError> catch e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@5be6e01c', suppressedCount=0
       rootCause=e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@691a7f8f', suppressedCount=0
  main @coroutine#1: coroutineScope catch e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@5be6e01c', suppressedCount=0
     rootCause=e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@691a7f8f', suppressedCount=0
                     *** main @coroutine#1: MyException.<ctor>('<AsyncComputeOfValueButThrowError> !!error!!')@7722c3c3 [recoverStackTrace -> duplicate exception]
 main @coroutine#1: runBlocking catch e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@7722c3c3', suppressedCount=0
    rootCause=e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@691a7f8f', suppressedCount=0
main: MAIN catch e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@7722c3c3', suppressedCount=0
   rootCause=e.toString='org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@691a7f8f', suppressedCount=0
```

The same could be seen in exception thrown stack trace logged into console with `uncaughtExceptionHandler`.

What we can see is that **first exception** `hashCode=5be6e01c` thrown from `CoroutineScope` is swallowed - it is set only as root-cause.

```
org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@7722c3c3
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:297)
	at org.example.MyCoroutines$runAsyncComputeOfValueButThrowError$r$1.invokeSuspend(Main.kt:107)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:284)
Caused by: org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@691a7f8f
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:297)
	at org.example.MyCoroutines$runAsyncComputeOfValueButThrowError$r$1.invokeSuspend(Main.kt:107)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.async(Builders.common.kt:88)
	at kotlinx.coroutines.BuildersKt.async(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.async$default(Builders.common.kt:79)
	at kotlinx.coroutines.BuildersKt.async$default(Unknown Source)
	at org.example.MyCoroutines.runAsyncComputeOfValueButThrowError(Main.kt:105)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:296)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:284)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:280)
	at org.example.MainKt.main(Main.kt)
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that no duplicate exception was created and stack trace is without "_COROUTINE._BOUNDARY" machinery. No need to repeat output or stack-trace.

## Use-case 4b: Async computation throwing the error, delay and throw NON-duplicable exception

Interesting stuff happens when Kotlin Coroutines machinery is unable to duplicate the original exception. In this case, only one
exception `hashCode=5276e6b0` is used as you can see from the log.

Execution is the same with "Use-case 4a".

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <AsyncComputeOfValueButThrowError> start
     main @coroutine#1: <AsyncComputeOfValueButThrowError> r-await begin
     main @coroutine#2: <AsyncComputeOfValueButThrowError> delaying 2000 ms
                     *** main @coroutine#2: MyExoticException.<ctor>('<AsyncComputeOfValueButThrowError> !!error!!', 1853389179)@5276e6b0 [original]
     main @coroutine#1: <AsyncComputeOfValueButThrowError> r-await catch e.toString='org.example.MyExoticException: <AsyncComputeOfValueButThrowError> !!error!!@5276e6b0', suppressedCount=0
    main @coroutine#1: <AsyncComputeOfValueButThrowError> catch e.toString='org.example.MyExoticException: <AsyncComputeOfValueButThrowError> !!error!!@5276e6b0', suppressedCount=0
  main @coroutine#1: coroutineScope catch e.toString='org.example.MyExoticException: <AsyncComputeOfValueButThrowError> !!error!!@5276e6b0', suppressedCount=0
 main @coroutine#1: runBlocking catch e.toString='org.example.MyExoticException: <AsyncComputeOfValueButThrowError> !!error!!@5276e6b0', suppressedCount=0
main: MAIN catch e.toString='org.example.MyExoticException: <AsyncComputeOfValueButThrowError> !!error!!@5276e6b0', suppressedCount=0
```

Stack trace is not modified and is similar to stack trace of `cause` exception from "Use-case 4a".

```
org.example.MyExoticException: <AsyncComputeOfValueButThrowError> !!error!!@5276e6b0
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:301)
	at org.example.MyCoroutines$runAsyncComputeOfValueButThrowError$r$1.invokeSuspend(Main.kt:108)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.async(Builders.common.kt:88)
	at kotlinx.coroutines.BuildersKt.async(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.async$default(Builders.common.kt:79)
	at kotlinx.coroutines.BuildersKt.async$default(Unknown Source)
	at org.example.MyCoroutines.runAsyncComputeOfValueButThrowError(Main.kt:106)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:300)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:285)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:281)
	at org.example.MainKt.main(Main.kt)
```

What is missing is **COROUTINE BOUNDARY** from "Use-case 4a".

```
org.example.MyException: <AsyncComputeOfValueButThrowError> !!error!!@7722c3c3
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:297)
	at org.example.MyCoroutines$runAsyncComputeOfValueButThrowError$r$1.invokeSuspend(Main.kt:107)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:284)
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that output is the same as when run without this option. Kotlin coroutines were unable to create duplicate exception.

## Use-case 5: Async computation but Job canceled after a while

In this Use-case, we want to show how Job cancellation works.

1. We start async `Job` and delay computation for 2 seconds, in the log as `async-computing-A`
2. We start another `Job` and delay computation for 200 seconds, in the log as `async-computing-B`
3. We also `await` for forst `async-computing-A` job to finish it's work
4. At the same time we launch another coroutine that after half-second cancels the job by calling `Deffered.cancel()` method.

From the log below, we can see that both jobs are canceled by `kotlinx.coroutines.JobCancellationException` with `hashCode=1cbbffcd`.

Interesting is that exception has same instance as `rootCause` - more details/questions below.

This one exception cancels the whole execution of coroutine.

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <AsyncComputeAndJobCancellation> start
     main @coroutine#2: <AsyncComputeAndJobCancellation> async-computing-A
     main @coroutine#2: <AsyncComputeAndJobCancellation> delaying 2000 ms
     main @coroutine#3: <AsyncComputeAndJobCancellation> cancel-job-A
     main @coroutine#3: <AsyncComputeAndJobCancellation> delaying 500 ms
     main @coroutine#4: <AsyncComputeAndJobCancellation> async-computing-B (for a long time)
     main @coroutine#4: <AsyncComputeAndJobCancellation> delaying 200000 ms
     main @coroutine#3: <AsyncComputeAndJobCancellation> cancel-job-A DONE
     main @coroutine#1: <AsyncComputeAndJobCancellation> await A catch e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
        rootCause=e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
    main @coroutine#1: <AsyncComputeAndJobCancellation> catch e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
       rootCause=e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
  main @coroutine#1: coroutineScope catch e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
     rootCause=e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
     main @coroutine#4: <AsyncComputeAndJobCancellation> async-computing-B catch e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
        rootCause=e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
 main @coroutine#1: runBlocking catch e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
    rootCause=e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
main: MAIN catch e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
   rootCause=e.toString='kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd', suppressedCount=0
```

From console log we can see that one `kotlinx.coroutines.JobCancellationException` is thrown.

What is interesting is even though it has same `hashCode=1cbbffcd`, stack frames are different.
Kotlin coroutines are known to heavily manipulate exception stack frames.

```
kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd
	at kotlinx.coroutines.JobSupport.cancel(JobSupport.kt:1685)
	at kotlinx.coroutines.Job$DefaultImpls.cancel$default(Job.kt:207)
	at org.example.MyCoroutines$runAsyncComputeAndJobCancellation$2.invokeSuspend(Main.kt:154)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MyCoroutines.awaitForResult(Main.kt:266)
	at org.example.MyCoroutines.runAsyncComputeAndJobCancellation(Main.kt:170)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:304)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:286)
Caused by: kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled; job="coroutine#2":DeferredCoroutine{Cancelled}@1cbbffcd
	at kotlinx.coroutines.JobSupport.cancel(JobSupport.kt:1685)
	at kotlinx.coroutines.Job$DefaultImpls.cancel$default(Job.kt:207)
	at org.example.MyCoroutines$runAsyncComputeAndJobCancellation$2.invokeSuspend(Main.kt:154)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
	at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
	at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
	at org.example.MyCoroutines.runAsyncComputeAndJobCancellation(Main.kt:151)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:304)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:286)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:282)
	at org.example.MainKt.main(Main.kt)
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that `kotlinx.coroutines.JobCancellationException` was created without filling the stack-trace.
You can see it in the implementation of method `fillInStackTrace()` here https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/jvm/src/Exceptions.kt#L45.

## Use-case 6a: Cancel coroutines with exception, but share rootCause exception with both coroutine stack frames, delay and throw duplicable exception

Now comes an interesting use-case. Let's describe what is happening here:

1. We `launch` coroutine `FIRST`, and delay it for 1 second
2. Then we `launch` two coroutines `AAA` and `BBB`, and delay them for 20 seconds - to mimic intensive computation.
3. After 1 second, when both delayed coroutines are in "intensive computation", we throw exception
4. Both coroutines `AAA` and `BBB` are canceled

What is interesting here is:

- Duplicate Exception `rootCause=02f7298b` is created from original exception `rootCause=0e50a6f6`
- Duplicate Exception is thrown from our code, and original is set as root-cause
- Coroutines `AAA` and `BBB` are canceled by `kotlinx.coroutines.JobCancellationException`, but they have the root-cause set to irrelevant exception `org.example.MyException("FIRST")@0e50a6f6`.

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <CoroutineWithIrrelevantException> start
     main @coroutine#1: <CoroutineWithIrrelevantException> launch-first out
    main @coroutine#1: <CoroutineWithIrrelevantException> end
  main @coroutine#1: coroutineScope end
     main @coroutine#2: <CoroutineWithIrrelevantException> launch-first
     main @coroutine#2: <CoroutineWithIrrelevantException> delaying 1000 ms
     main @coroutine#3: <CoroutineWithIrrelevantException> AAA-launch start
     main @coroutine#3: <CoroutineWithIrrelevantException> delaying 20000 ms
     main @coroutine#4: <CoroutineWithIrrelevantException> BBB-launch start
     main @coroutine#4: <CoroutineWithIrrelevantException> delaying 20000 ms
                     *** main @coroutine#2: MyException.<ctor>('FIRST')@0e50a6f6 [original]
     main @coroutine#3: <CoroutineWithIrrelevantException> AAA-launch catch e.toString='kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#1":ScopeCoroutine{Cancelling}@619713e5', suppressedCount=0
        rootCause=e.toString='org.example.MyException: FIRST@0e50a6f6', suppressedCount=0
     main @coroutine#4: <CoroutineWithIrrelevantException> BBB-launch catch e.toString='kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#1":ScopeCoroutine{Cancelling}@619713e5', suppressedCount=0
        rootCause=e.toString='org.example.MyException: FIRST@0e50a6f6', suppressedCount=0
                     *** main @coroutine#4: MyException.<ctor>('FIRST')@02f7298b [recoverStackTrace -> duplicate exception]
 main @coroutine#1: runBlocking catch e.toString='org.example.MyException: FIRST@02f7298b', suppressedCount=0
    rootCause=e.toString='org.example.MyException: FIRST@0e50a6f6', suppressedCount=0
main: MAIN catch e.toString='org.example.MyException: FIRST@02f7298b', suppressedCount=0
   rootCause=e.toString='org.example.MyException: FIRST@0e50a6f6', suppressedCount=0
```

The same could be seen in exception thrown stack trace logged into console with `uncaughtExceptionHandler`.

What we can see is that **first exception** `hashCode=0e50a6f6` thrown from `CoroutineScope` is swallowed - it is set only as root-cause.

```
org.example.MyException: FIRST@02f7298b
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:304)
	at org.example.MyCoroutines$runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$1.invokeSuspend(Main.kt:188)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:284)
Caused by: org.example.MyException: FIRST@0e50a6f6
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:304)
	at org.example.MyCoroutines$runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$1.invokeSuspend(Main.kt:188)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
	at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
	at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
	at org.example.MyCoroutines.runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(Main.kt:185)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:303)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:284)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:280)
	at org.example.MainKt.main(Main.kt)
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that no duplicate exception was created and stack trace is without "_COROUTINE._BOUNDARY" machinery. No need to repeat output or stack-trace.

## Use-case 6b: Cancel coroutines with exception, but share rootCause exception with both coroutine stack frames, delay and throw NON-duplicable exception

Interesting stuff happens when Kotlin Coroutines machinery is unable to duplicate the original exception. In this case, only one
exception `hashCode=05dd6264` is used as you can see from the log.

Execution is the same with "Use-case 6a".

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <CoroutineWithIrrelevantException> start
     main @coroutine#1: <CoroutineWithIrrelevantException> launch-first out
    main @coroutine#1: <CoroutineWithIrrelevantException> end
  main @coroutine#1: coroutineScope end
     main @coroutine#2: <CoroutineWithIrrelevantException> launch-first
     main @coroutine#2: <CoroutineWithIrrelevantException> delaying 1000 ms
     main @coroutine#3: <CoroutineWithIrrelevantException> AAA-launch start
     main @coroutine#3: <CoroutineWithIrrelevantException> delaying 20000 ms
     main @coroutine#4: <CoroutineWithIrrelevantException> BBB-launch start
     main @coroutine#4: <CoroutineWithIrrelevantException> delaying 20000 ms
                     *** main @coroutine#2: MyExoticException.<ctor>('FIRST', 1699182965)@05dd6264 [original]
     main @coroutine#3: <CoroutineWithIrrelevantException> AAA-launch catch e.toString='kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#1":ScopeCoroutine{Cancelling}@351d00c0', suppressedCount=0
        rootCause=e.toString='org.example.MyExoticException: FIRST@05dd6264', suppressedCount=0
     main @coroutine#4: <CoroutineWithIrrelevantException> BBB-launch catch e.toString='kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#1":ScopeCoroutine{Cancelling}@351d00c0', suppressedCount=0
        rootCause=e.toString='org.example.MyExoticException: FIRST@05dd6264', suppressedCount=0
 main @coroutine#1: runBlocking catch e.toString='org.example.MyExoticException: FIRST@05dd6264', suppressedCount=0
main: MAIN catch e.toString='org.example.MyExoticException: FIRST@05dd6264', suppressedCount=0
```

Stack trace is not modified and is similar to stack trace of `cause` exception from "Use-case 6a".

```
org.example.MyExoticException: FIRST@05dd6264
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:308)
	at org.example.MyCoroutines$runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$1.invokeSuspend(Main.kt:189)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
	at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
	at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
	at org.example.MyCoroutines.runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(Main.kt:186)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:307)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:285)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:281)
	at org.example.MainKt.main(Main.kt)
```

What is missing is **COROUTINE BOUNDARY** from "Use-case 6a".

```
org.example.MyException: FIRST@02f7298b
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:304)
	at org.example.MyCoroutines$runThrowInAsyncCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$1.invokeSuspend(Main.kt:188)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:284)
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that output is the same as when run without this option. Kotlin coroutines were unable to create duplicate exception.

## Use-case 7a: Two async coroutine computations, canceled by two async coroutines, delay and throw duplicable exception

We can expand the behavior of "Use-case 6" with real concurrent execution:

1. We start coroutine `ex-AAA` that will throw exception after 2 seconds
2. At same time we start coroutine `ex-BBB` that will throw exception after 2 seconds
3. Both coroutines are started in different Threads using executor service (in this case `Dispatchers.IO`)
4. We cannot guarantee which coroutine will cancel whole `CoroutineScope`
5. During processing, we start two "intensive computation" coroutines `wait-FIRST` and `wait-SECOND`, who delay for 20 seconds
6. When an exception is thrown after 2 seconds, we do not know which coroutine (`ex-AAA` or `ex-BBB`) is the root-cause of it.

We can see similar patterns like in previous Use-cases:

- Two original exceptions are created
    - `MyException.<ctor>('FOO-ex-BBB-BAR')@3d75c743`
    - `MyException.<ctor>('FOO-ex-AAA-BAR')@6bd0b7bc`
- Then Kotlin coroutines "decide" which one will be **master**, in our case it is exception from coroutine `ex-BBB`
- We can find it when there is duplicate of this exception created, with `hashCode=5e82df6a`
- This duplicate exception from coroutine `ex-BBB` has original exception `hashCode=3d75c743` as root-cause
- And we did not lose exception from second coroutine `ex-AAA`, because it is attached as suppressed exception

**Note:** Depends on execution but which coroutine, and its exception will become **master**, is totally random.
Each run of this sample program will return them in different "order".

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <DoubleThrowsCoroutineWithIrrelevantException> start
     main @coroutine#1: <DoubleThrowsCoroutineWithIrrelevantException> computing out
     DefaultDispatcher-worker-1 @coroutine#2: <DoubleThrowsCoroutineWithIrrelevantException> ex-AAA computing
     DefaultDispatcher-worker-1 @coroutine#2: <DoubleThrowsCoroutineWithIrrelevantException> delaying 2000 ms
     main @coroutine#1: <DoubleThrowsCoroutineWithIrrelevantException> computing out
     DefaultDispatcher-worker-3 @coroutine#3: <DoubleThrowsCoroutineWithIrrelevantException> ex-BBB computing
     DefaultDispatcher-worker-3 @coroutine#3: <DoubleThrowsCoroutineWithIrrelevantException> delaying 2000 ms
    main @coroutine#1: <DoubleThrowsCoroutineWithIrrelevantException> end
  main @coroutine#1: coroutineScope end
     main @coroutine#4: <DoubleThrowsCoroutineWithIrrelevantException> wait-FIRST start
     main @coroutine#4: <DoubleThrowsCoroutineWithIrrelevantException> delaying 20000 ms
     main @coroutine#5: <DoubleThrowsCoroutineWithIrrelevantException> wait-SECOND start
     main @coroutine#5: <DoubleThrowsCoroutineWithIrrelevantException> delaying 20000 ms
                     *** DefaultDispatcher-worker-1 @coroutine#3: MyException.<ctor>('FOO-ex-BBB-BAR')@3d75c743 [original]
                     *** DefaultDispatcher-worker-3 @coroutine#2: MyException.<ctor>('FOO-ex-AAA-BAR')@6bd0b7bc [original]
     main @coroutine#4: <DoubleThrowsCoroutineWithIrrelevantException> wait-FIRST catch e.toString='kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#1":ScopeCoroutine{Cancelling}@34b7ac2f', suppressedCount=0
        rootCause=e.toString='org.example.MyException: FOO-ex-BBB-BAR@3d75c743', suppressedCount=0
     main @coroutine#5: <DoubleThrowsCoroutineWithIrrelevantException> wait-SECOND catch e.toString='kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#1":ScopeCoroutine{Cancelling}@34b7ac2f', suppressedCount=0
        rootCause=e.toString='org.example.MyException: FOO-ex-BBB-BAR@3d75c743', suppressedCount=0
                     *** main @coroutine#5: MyException.<ctor>('FOO-ex-BBB-BAR')@5e82df6a [recoverStackTrace -> duplicate exception]
 main @coroutine#1: runBlocking catch e.toString='org.example.MyException: FOO-ex-BBB-BAR@5e82df6a', suppressedCount=0
    rootCause=e.toString='org.example.MyException: FOO-ex-BBB-BAR@3d75c743', suppressedCount=1
main: MAIN catch e.toString='org.example.MyException: FOO-ex-BBB-BAR@5e82df6a', suppressedCount=0
   rootCause=e.toString='org.example.MyException: FOO-ex-BBB-BAR@3d75c743', suppressedCount=1
```

The same could be seen in exception thrown stack trace logged into console with `uncaughtExceptionHandler`.

What we can see is that **first exception** `hashCode=3d75c743` thrown from `CoroutineScope` is swallowed - it is set only as root-cause.
And it also has suppressed exception from **second exception** `hashCode=6bd0b7bc`.

```
org.example.MyException: FOO-ex-BBB-BAR@5e82df6a
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:312)
	at org.example.MyCoroutines$runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow$1.invokeSuspend(Main.kt:231)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:286)
Caused by: org.example.MyException: FOO-ex-BBB-BAR@3d75c743
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:312)
	at org.example.MyCoroutines$runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow$1.invokeSuspend(Main.kt:231)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
	at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
	at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
	at org.example.MyCoroutines.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow(Main.kt:228)
	at org.example.MyCoroutines.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(Main.kt:241)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:311)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:286)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:282)
	at org.example.MainKt.main(Main.kt)
	Suppressed: org.example.MyException: FOO-ex-AAA-BAR@6bd0b7bc
		at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:312)
		at org.example.MyCoroutines$runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow$1.invokeSuspend(Main.kt:231)
		at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
		at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
		at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
		at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
		at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
		at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
		at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
		at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
		at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
		at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
		at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
		at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
		at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
		at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
		at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
		at org.example.MyCoroutines.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow(Main.kt:228)
		at org.example.MyCoroutines.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(Main.kt:240)
		... 24 more
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that no duplicate exception was created and stack trace is without "_COROUTINE._BOUNDARY" machinery. No need to repeat output or stack-trace.

## Use-case 7b: Two async coroutine computations, canceled by two async coroutines, delay and throw NON-duplicable exception

Interesting stuff happens when Kotlin Coroutines machinery is unable to duplicate the original exception. In this case, only
exceptions `hashCode=67e6cdde` and `hashCode=776eda48` from coroutines `ex-AAA` and `ex-BBB` are used as you can see from the log.

Execution is the same with "Use-case 7a".

```
main: MAIN begin
 main @coroutine#1: runBlocking begin
  main @coroutine#1: coroutineScope begin
    main @coroutine#1: <DoubleThrowsCoroutineWithIrrelevantException> start
     main @coroutine#1: <DoubleThrowsCoroutineWithIrrelevantException> computing out
     DefaultDispatcher-worker-1 @coroutine#2: <DoubleThrowsCoroutineWithIrrelevantException> ex-AAA computing
     DefaultDispatcher-worker-1 @coroutine#2: <DoubleThrowsCoroutineWithIrrelevantException> delaying 2000 ms
     main @coroutine#1: <DoubleThrowsCoroutineWithIrrelevantException> computing out
     DefaultDispatcher-worker-3 @coroutine#3: <DoubleThrowsCoroutineWithIrrelevantException> ex-BBB computing
     DefaultDispatcher-worker-3 @coroutine#3: <DoubleThrowsCoroutineWithIrrelevantException> delaying 2000 ms
    main @coroutine#1: <DoubleThrowsCoroutineWithIrrelevantException> end
  main @coroutine#1: coroutineScope end
     main @coroutine#4: <DoubleThrowsCoroutineWithIrrelevantException> wait-FIRST start
     main @coroutine#4: <DoubleThrowsCoroutineWithIrrelevantException> delaying 20000 ms
     main @coroutine#5: <DoubleThrowsCoroutineWithIrrelevantException> wait-SECOND start
     main @coroutine#5: <DoubleThrowsCoroutineWithIrrelevantException> delaying 20000 ms
                     *** DefaultDispatcher-worker-3 @coroutine#2: MyExoticException.<ctor>('FOO-ex-AAA-BAR', 1699916506)@67e6cdde [original]
                     *** DefaultDispatcher-worker-1 @coroutine#3: MyExoticException.<ctor>('FOO-ex-BBB-BAR', 1290859805)@776eda48 [original]
     main @coroutine#4: <DoubleThrowsCoroutineWithIrrelevantException> wait-FIRST catch e.toString='kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#1":ScopeCoroutine{Cancelling}@10e41621', suppressedCount=0
        rootCause=e.toString='org.example.MyExoticException: FOO-ex-AAA-BAR@67e6cdde', suppressedCount=0
     main @coroutine#5: <DoubleThrowsCoroutineWithIrrelevantException> wait-SECOND catch e.toString='kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#1":ScopeCoroutine{Cancelling}@10e41621', suppressedCount=0
        rootCause=e.toString='org.example.MyExoticException: FOO-ex-AAA-BAR@67e6cdde', suppressedCount=0
 main @coroutine#1: runBlocking catch e.toString='org.example.MyExoticException: FOO-ex-AAA-BAR@67e6cdde', suppressedCount=1
main: MAIN catch e.toString='org.example.MyExoticException: FOO-ex-AAA-BAR@67e6cdde', suppressedCount=1
```

Stack trace is not modified and is similar to stack trace of `cause` exception from "Use-case 7a".

```
org.example.MyExoticException: FOO-ex-AAA-BAR@67e6cdde
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:314)
	at org.example.MyCoroutines$runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow$1.invokeSuspend(Main.kt:230)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
	at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
	at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
	at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
	at org.example.MyCoroutines.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow(Main.kt:227)
	at org.example.MyCoroutines.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(Main.kt:239)
	at org.example.MainKt$main$1$1.invokeSuspend(Main.kt:313)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at org.example.MainKt$main$1$1.invoke(Main.kt)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndspatched(Undispatched.kt:66)
	at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:43)
	at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:286)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:285)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
	at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
	at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
	at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
	at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
	at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
	at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
	at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at org.example.MainKt.main(Main.kt:281)
	at org.example.MainKt.main(Main.kt)
	Suppressed: org.example.MyExoticException: FOO-ex-BBB-BAR@776eda48
		at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:314)
		at org.example.MyCoroutines$runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow$1.invokeSuspend(Main.kt:230)
		at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith$$$capture(ContinuationImpl.kt:33)
		at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt)
		at --- Async.Stack.Trace --- (captured by IntelliJ IDEA debugger)
		at kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner.<init>(DebugProbesImpl.kt:531)
		at kotlinx.coroutines.debug.internal.DebugProbesImpl.createOwner(DebugProbesImpl.kt:510)
		at kotlinx.coroutines.debug.internal.DebugProbesImpl.probeCoroutineCreated$kotlinx_coroutines_core(DebugProbesImpl.kt:497)
		at kotlin.coroutines.jvm.internal.DebugProbesKt.probeCoroutineCreated(DebugProbes.kt:7)
		at kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:161)
		at kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:26)
		at kotlinx.coroutines.CoroutineStart.invoke(CoroutineStart.kt:358)
		at kotlinx.coroutines.AbstractCoroutine.start(AbstractCoroutine.kt:134)
		at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:53)
		at kotlinx.coroutines.BuildersKt.launch(Unknown Source)
		at kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(Builders.common.kt:44)
		at kotlinx.coroutines.BuildersKt.launch$default(Unknown Source)
		at org.example.MyCoroutines.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow(Main.kt:227)
		at org.example.MyCoroutines.runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine(Main.kt:240)
		... 24 more
```

What is missing is **COROUTINE BOUNDARY** from "Use-case 7a".

```
org.example.MyException: FOO-ex-BBB-BAR@5e82df6a
	at org.example.MainKt$main$1$1.invokeSuspend$lambda$0(Main.kt:312)
	at org.example.MyCoroutines$runDoubleThrowsInAsyncThreadedCoroutineAndCancelOtherOnesDelayedWithExceptionFromOriginalCoroutine$doLaunchInIOAndThrow$1.invokeSuspend(Main.kt:231)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at org.example.MainKt$main$1.invokeSuspend(Main.kt:286)
```

Code executed above was with run with `-Dkotlinx.coroutines.debug` JVM option.

Without this option, we can see that output is the same as when run without this option. Kotlin coroutines were unable to create duplicate exception.
