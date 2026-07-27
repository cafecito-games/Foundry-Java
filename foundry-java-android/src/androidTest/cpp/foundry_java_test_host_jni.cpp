#include "foundry_java_fake_extension_host.h"

#include <jni.h>

#include <string>

#define FOUNDRY_JAVA_TEST_HOST_JNI_EXPORT                                      \
  extern "C" JNIEXPORT __attribute__((visibility("default")))

namespace {

jstring to_java_string(JNIEnv *environment, const std::string &value) {
  if (environment == nullptr) {
    return nullptr;
  }
  return environment->NewStringUTF(value.c_str());
}

} // namespace

FOUNDRY_JAVA_TEST_HOST_JNI_EXPORT jint JNICALL
JNI_OnLoad(JavaVM *java_vm, void *) {
  JNIEnv *environment = nullptr;
  if (java_vm == nullptr ||
      java_vm->GetEnv(reinterpret_cast<void **>(&environment),
                      JNI_VERSION_1_6) != JNI_OK ||
      environment == nullptr) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}

FOUNDRY_JAVA_TEST_HOST_JNI_EXPORT jstring JNICALL
Java_games_cafecito_foundry_java_FoundryJavaTestHost_nativePreEntryEvidenceV1(
    JNIEnv *environment, jclass) {
  return to_java_string(environment,
                        foundry_java_test_host::pre_entry_evidence_v1());
}

FOUNDRY_JAVA_TEST_HOST_JNI_EXPORT jstring JNICALL
Java_games_cafecito_foundry_java_FoundryJavaTestHost_nativeRunLifecycleV1(
    JNIEnv *environment, jclass, jint run_index) {
  return to_java_string(environment, foundry_java_test_host::run_lifecycle_v1(
                                         static_cast<int>(run_index)));
}
