#pragma once

#include "foundry_extension_interface.h"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace foundry_java {

using ContextHandle = std::uint64_t;
struct BridgeServices;
class NativeTransport;

enum class ContextOperationKind : std::uint8_t {
	ORDINARY,
	CLEANUP,
};

class ContextOperationLease final {
public:
	ContextOperationLease();
	~ContextOperationLease();
	ContextOperationLease(ContextOperationLease &&other) noexcept;
	ContextOperationLease &operator=(ContextOperationLease &&other) noexcept;

	ContextOperationLease(const ContextOperationLease &) = delete;
	ContextOperationLease &operator=(const ContextOperationLease &) = delete;

	explicit operator bool() const noexcept;
	std::uint64_t generation() const noexcept;
	std::shared_ptr<const BridgeServices> services() const noexcept;
	NativeTransport *transport() const noexcept;
	FoundryExtensionClassLibraryPtr library() const noexcept;

private:
	struct Impl;
	explicit ContextOperationLease(std::unique_ptr<Impl> impl);
	std::unique_ptr<Impl> impl;

	friend class BridgeRuntime;
};

class ErrorSink {
public:
	virtual ~ErrorSink() = default;
	virtual void error(const std::string &message) noexcept = 0;
};

class CallbackTarget {
public:
	virtual ~CallbackTarget() = default;
	virtual bool initialize(ContextHandle context, std::int32_t level) = 0;
	virtual void deinitialize(ContextHandle context, std::int32_t level) = 0;
	virtual std::int64_t invoke(
			ContextHandle context,
			std::int64_t callback,
			const std::vector<std::int64_t> &arguments) = 0;
	virtual void invalidate(ContextHandle context) = 0;
};

class BridgeRuntime final {
public:
	BridgeRuntime(std::shared_ptr<CallbackTarget> callbacks, std::shared_ptr<ErrorSink> errors);
	~BridgeRuntime();

	BridgeRuntime(const BridgeRuntime &) = delete;
	BridgeRuntime &operator=(const BridgeRuntime &) = delete;

	ContextHandle create_context();
	ContextHandle create_native_context();
	bool initialize(ContextHandle context, std::int32_t level) noexcept;
	void deinitialize(ContextHandle context, std::int32_t level) noexcept;
	std::int64_t invoke(
			ContextHandle context,
			std::int64_t callback,
			const std::vector<std::int64_t> &arguments) noexcept;
	ContextOperationLease acquire_operation(
			ContextHandle context,
			ContextOperationKind kind = ContextOperationKind::ORDINARY) noexcept;
	bool install_native_services(
			std::shared_ptr<const BridgeServices> services,
			FoundryExtensionClassLibraryPtr library) noexcept;
	void set_context_teardown(
			std::function<void(ContextHandle, std::uint64_t)> teardown) noexcept;
	bool shutdown_context(ContextHandle context, std::int32_t level) noexcept;
	void begin_new_generation() noexcept;
	bool shutdown_all(std::int32_t level) noexcept;

private:
	struct Impl;
	std::unique_ptr<Impl> impl;
};

/**
 * JNI-agnostic reservation state for the process bootstrap/shutdown handoff.
 *
 * Loader and VM values are opaque tokens; JNI reference creation/deletion stays in the JNI layer.
 */
class JniTransitionState final {
public:
	using Token = std::uintptr_t;
	using Ticket = std::uint64_t;

	JniTransitionState();
	~JniTransitionState();

	JniTransitionState(const JniTransitionState &) = delete;
	JniTransitionState &operator=(const JniTransitionState &) = delete;

	bool install(Token java_vm, Token class_loader) noexcept;
	Ticket reserve_bootstrap(Token &java_vm) noexcept;
	bool publish_bootstrap(
			Ticket ticket,
			std::shared_ptr<BridgeRuntime> runtime,
			Token requested_class_loader,
			Token &previous_class_loader) noexcept;
	bool cancel_bootstrap(Ticket ticket) noexcept;
	Ticket reserve_shutdown(std::shared_ptr<BridgeRuntime> &runtime) noexcept;
	bool finish_shutdown(
			Ticket ticket,
			const std::shared_ptr<BridgeRuntime> &runtime,
			Token &java_vm,
			Token &class_loader) noexcept;
	bool cancel_shutdown(
			Ticket ticket,
			const std::shared_ptr<BridgeRuntime> &runtime) noexcept;
	std::shared_ptr<BridgeRuntime> runtime() const noexcept;
	Token java_vm() const noexcept;
	Token pin_class_loader(const std::function<Token(Token)> &pin) const;
	bool ready() const noexcept;
	void clear_java_vm() noexcept;

private:
	struct Impl;
	std::unique_ptr<Impl> impl;
};

bool jni_bridge_is_ready() noexcept;
ContextHandle jni_bridge_create_context() noexcept;
bool jni_bridge_initialize(ContextHandle context, std::int32_t level) noexcept;
void jni_bridge_deinitialize(ContextHandle context, std::int32_t level) noexcept;
bool jni_bridge_shutdown_context(ContextHandle context, std::int32_t level) noexcept;
void jni_bridge_install_foundry_error_interface(FoundryExtensionInterfacePrintError print_error) noexcept;
bool jni_bridge_install_native_services(
		std::shared_ptr<const BridgeServices> services,
		FoundryExtensionClassLibraryPtr library) noexcept;
bool jni_bridge_shutdown() noexcept;

FoundryExtensionBool initialize_extension(
		FoundryExtensionInterfaceGetProcAddress get_proc_address,
		FoundryExtensionClassLibraryPtr library,
		FoundryExtensionInitialization *initialization);

} // namespace foundry_java
