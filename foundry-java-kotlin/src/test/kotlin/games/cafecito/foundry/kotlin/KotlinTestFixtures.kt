package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryBindingContext
import games.cafecito.foundry.runtime.FoundryClassDescriptor
import games.cafecito.foundry.runtime.FoundryEngine
import games.cafecito.foundry.runtime.FoundryObject
import games.cafecito.foundry.runtime.ObjectLease
import games.cafecito.foundry.types.Variant

internal class TestEngine : FoundryEngine {
    var objectType = ""
    var valid = true
    var callResult = FoundryEngine.CallResult.success(Variant.nil())
    var recordedArguments = emptyList<Variant>()

    override fun registerExtensionClass(
        contextHandle: Long,
        descriptor: FoundryClassDescriptor,
    ) = Unit

    override fun unregisterExtensionClass(
        contextHandle: Long,
        foundryName: String,
    ) = Unit

    override fun call(
        contextHandle: Long,
        objectHandle: Long,
        methodIdentity: String,
        arguments: List<Variant>,
    ): FoundryEngine.CallResult {
        recordedArguments = arguments
        return callResult
    }

    override fun decodeVariant(
        contextHandle: Long,
        variantHandle: Long,
    ): Variant = Variant.nil()

    override fun encodeVariant(
        contextHandle: Long,
        value: Variant,
    ): Long = 0

    override fun isObjectValid(
        contextHandle: Long,
        objectHandle: Long,
    ): Boolean = valid

    override fun objectType(
        contextHandle: Long,
        objectHandle: Long,
    ): String = objectType

    override fun instantiate(
        contextHandle: Long,
        className: String,
    ): Long = 1

    override fun retain(
        contextHandle: Long,
        objectHandle: Long,
    ) = Unit

    override fun release(
        contextHandle: Long,
        objectHandle: Long,
    ) = Unit

    override fun singleton(
        contextHandle: Long,
        name: String,
    ): Long = 1

    override fun reportCallbackException(
        contextHandle: Long,
        callbackHandle: Long,
        failure: Throwable,
    ) = Unit
}

internal class TestObject(
    context: FoundryBindingContext,
    lease: ObjectLease,
) : FoundryObject(context, lease)

internal class OtherObject(
    context: FoundryBindingContext,
    lease: ObjectLease,
) : FoundryObject(context, lease)

internal fun testContext(engine: TestEngine = TestEngine()): FoundryBindingContext = FoundryBindingContext(11, engine)
