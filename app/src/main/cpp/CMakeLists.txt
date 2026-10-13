# Motor de IA en el dispositivo: llama.cpp (texto) + mtmd (visión) detrás de una capa JNI (libllm_jni.so).
#
# arm64-v8a : backend CPU en VARIANTES dinámicas (libggml-cpu-android_armv8.0_1.so ... armv9.2_2.so). ggml elige en
#             ejecución la mejor que el procesador soporta (dotprod / fp16 / i8mm / SVE / SME), así que un Cortex-A53
#             viejo usa la genérica armv8.0 y no revienta con SIGILL, y un móvil moderno usa i8mm.
# armeabi-v7a: backend CPU único (NEON + VFPv4), sin variantes.
# x86_64    : backend CPU único genérico (emulador).
cmake_minimum_required(VERSION 3.22.1)
project(llm_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
get_filename_component(LLAMA_DIR ${LLAMA_DIR} ABSOLUTE)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
# -O3 en todo llama.cpp/ggml (en Release CMake ya pone -O3; RelWithDebInfo de AGP pondría -O2).
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_RELWITHDEBINFO   "-O3 -DNDEBUG -g")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -DNDEBUG -g")

# Secciones separadas para que el enlazador tire el código que no se usa (llama-common es grande).
# -g0: sin información de depuración (el NDK pone -g; los objetos de llama.cpp pasan de ~600 MB a ~150 MB por ABI
# y las .so se recortan igualmente al empaquetar).
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -g0")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -g0")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,--as-needed")

# Opciones de llama.cpp comunes a todas las ABIs.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)    # plantillas de chat (jinja) del GGUF
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)      # visión (libmtmd) sin el resto de tools/
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)          # nunca -march=native (se compila en x86)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)          # hilos propios de ggml (libomp del NDK da problemas al empaquetar)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)  # descarga código en la configuración: fuera
    set(LLM_BACKEND_DL 1)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(LLM_BACKEND_DL 0)
else()
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    # x86_64 genérico (sin AVX: el emulador por TCG no siempre lo tiene).
    set(GGML_AVX OFF CACHE BOOL "" FORCE)
    set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
    set(GGML_FMA OFF CACHE BOOL "" FORCE)
    set(GGML_F16C OFF CACHE BOOL "" FORCE)
    set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
    set(GGML_AVX512 OFF CACHE BOOL "" FORCE)
    set(LLM_BACKEND_DL 0)
endif()

add_subdirectory(${LLAMA_DIR} build-llama)

add_library(llm_jni SHARED llm_jni.cpp llm_session.cpp)
target_include_directories(llm_jni PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/common
        ${LLAMA_DIR}/tools/mtmd
        ${LLAMA_DIR}/ggml/include)
target_compile_definitions(llm_jni PRIVATE LLM_BACKEND_DL=${LLM_BACKEND_DL})
target_link_libraries(llm_jni PRIVATE mtmd llama-common llama ggml jnigraphics android log)
