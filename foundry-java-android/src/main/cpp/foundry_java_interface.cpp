#include "foundry_java_interface.h"

#include <cstring>
#include <utility>

namespace foundry_java {

namespace {

template <typename Function>
Function load_interface(FoundryExtensionInterfaceGetProcAddress get_proc_address, const char *name) {
	const FoundryExtensionInterfaceFunctionPtr raw = get_proc_address(name);
	Function function = nullptr;
	static_assert(sizeof(function) == sizeof(raw));
	std::memcpy(&function, &raw, sizeof(function));
	return function;
}

} // namespace

BridgeResolution resolve_bridge_services(FoundryExtensionInterfaceGetProcAddress get_proc_address) {
	if (get_proc_address == nullptr) {
		return { nullptr, "get_proc_address", nullptr };
	}
	BridgeServices services;
#define FOUNDRY_JAVA_RESOLVE_SERVICE(member, type, name) \
	services.member = load_interface<type>(get_proc_address, name); \
	if (services.member == nullptr) { \
		return { nullptr, name, services.print_error }; \
	}
	FOUNDRY_JAVA_BRIDGE_SERVICE_LIST(FOUNDRY_JAVA_RESOLVE_SERVICE)
#undef FOUNDRY_JAVA_RESOLVE_SERVICE
	const auto print_error = services.print_error;
	return { std::make_shared<const BridgeServices>(std::move(services)), {}, print_error };
}

const std::array<std::string_view, 60> &required_bridge_service_names() {
	static constexpr std::array<std::string_view, 60> names = { {
#define FOUNDRY_JAVA_SERVICE_NAME(member, type, name) name,
		FOUNDRY_JAVA_BRIDGE_SERVICE_LIST(FOUNDRY_JAVA_SERVICE_NAME)
#undef FOUNDRY_JAVA_SERVICE_NAME
	} };
	return names;
}

} // namespace foundry_java
