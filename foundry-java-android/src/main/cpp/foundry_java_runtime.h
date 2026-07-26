#pragma once

#include "foundry_extension_interface.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace foundry_java {

using ContextHandle = std::uint64_t;

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
	bool initialize(ContextHandle context, std::int32_t level) noexcept;
	void deinitialize(ContextHandle context, std::int32_t level) noexcept;
	std::int64_t invoke(
			ContextHandle context,
			std::int64_t callback,
			const std::vector<std::int64_t> &arguments) noexcept;
	bool shutdown_context(ContextHandle context, std::int32_t level) noexcept;
	void begin_new_generation() noexcept;
	bool shutdown_all(std::int32_t level) noexcept;

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
void jni_bridge_shutdown() noexcept;

FoundryExtensionBool initialize_extension(
		FoundryExtensionInterfaceGetProcAddress get_proc_address,
		FoundryExtensionClassLibraryPtr library,
		FoundryExtensionInitialization *initialization);

} // namespace foundry_java
