extends Node

# The only evidence the engine-loaded conformance gate accepts. Absence of a crash proves nothing,
# so the marker is printed only after the engine's class database resolved the Java-defined class,
# instantiated it, and returned the value the Java method computed. Every other outcome pushes a
# distinct failure reason so the gate reports the real cause instead of a bare timeout.
func _ready() -> void:
	if not ClassDB.class_exists("FoundryJavaEngineProbe"):
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED class_missing")
		return
	var probe: Object = ClassDB.instantiate("FoundryJavaEngineProbe")
	if probe == null:
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED instantiate_failed")
		return
	if not probe.has_method("engine_probe"):
		probe.free()
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED method_missing")
		return
	var result: int = probe.call("engine_probe", 41)
	probe.free()
	if result != 42:
		push_error("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED probe_result")
		return
	print("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_READY")
