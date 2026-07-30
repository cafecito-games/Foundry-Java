extends Node

# The only evidence the engine-loaded conformance gate accepts. Absence of a crash proves nothing,
# so the marker is printed only after the engine's class database resolved the Java-defined class,
# instantiated it, returned the value the Java method computed, dispatched a virtual override into
# Java, and round-tripped a property through the engine's property system. Every other outcome
# pushes a distinct failure reason so the gate reports the real cause instead of a bare timeout.
func _ready() -> void:
	if not ClassDB.class_exists("FoundryJavaEngineProbe"):
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED class_missing")
		return
	var probe: Node = ClassDB.instantiate("FoundryJavaEngineProbe") as Node
	if probe == null:
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED instantiate_failed")
		return
	if not probe.has_method("engine_probe"):
		probe.free()
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED method_missing")
		return
	var result: int = probe.call("engine_probe", 41)
	if result != 42:
		probe.free()
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED probe_result")
		return
	# Entering the tree is the engine's own call into Java. Nothing in this script dispatches
	# _ready, so a count of one can only come from the engine resolving the override by its
	# exported Foundry name and then dispatching it by the Java name the binding recorded.
	if not probe.has_method("ready_dispatch_count"):
		probe.free()
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED counter_missing")
		return
	add_child(probe)
	var dispatches: int = probe.call("ready_dispatch_count")
	remove_child(probe)
	if dispatches != 1:
		probe.free()
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED virtual_not_dispatched")
		return
	probe.set("probe_scale", 7)
	var scale: int = probe.get("probe_scale")
	probe.free()
	if scale != 7:
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED property_roundtrip")
		return
	print("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_READY")
