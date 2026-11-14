// The app's way into Azahar (3DS games). For now one call: what a cart file is, before anything boots it.
#include <jni.h>

#include <memory>
#include <string>

#include "core/loader/loader.h"

namespace {

std::string fromJava(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

// "<status>\t<program id hex>\t<title>": status is Loader::ResultStatus (0 = Success, 8 = ErrorEncrypted, ...); the id
// and title are empty when the cart couldn't be read that far. null when no loader knows the file at all.
extern "C" JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_n3ds_N3dsNative_probe(JNIEnv* env, jclass, jstring path) {
    std::unique_ptr<Loader::AppLoader> loader = Loader::GetLoader(fromJava(env, path));
    if (!loader) return nullptr;
    u64 programId = 0;
    const Loader::ResultStatus status = loader->ReadProgramId(programId);
    std::string title;
    if (status == Loader::ResultStatus::Success) loader->ReadTitle(title);
    char id[17] = "";
    if (status == Loader::ResultStatus::Success) snprintf(id, sizeof id, "%016llx", (unsigned long long)programId);
    const std::string out = std::to_string(static_cast<int>(status)) + "\t" + id + "\t" + title;
    return env->NewStringUTF(out.c_str());
}
