# onnxruntime.jar

[ONNX Runtime](https://github.com/microsoft/onnxruntime) is a cross-platform
inference accelerator. It is needed to run the human pose estimation models.

The ONNX Runtime package published by Microsoft in
[com.microsoft.onnxruntime:onnxruntime](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime)
only supports CPU inference. Running human pose estimation at 30fps on CPU is
possible, but takes a lot of CPU and has high latency.

The ONNX Runtime GPU package in
[com.microsoft.onnxruntime:onnxruntime_gpu](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime_gpu)
supports CUDA on NVIDIA graphics cards, but requires the user to also install
the [NVIDIA CUDA toolkit](https://developer.nvidia.com/cuda/toolkit) and the
[NVIDIA cuDNN library](https://developer.nvidia.com/cudnn) which also takes up
many GB of disk space. This doesn't work on AMD or Intel graphics cards.

ONNX Runtime offers a DirectML solution which runs on all graphics cards.
Unfortunately, the published packages do not support DirectML, so we need to
build ONNX Runtime from scratch.

There is no common solution for Linux, so ONNX Runtime will just use CPU.

## Building ONNX Runtime

To create a repeatable build, we create a new "Windows 10 MSIX Packaging
Environment" virtual machine using "Hyper-V Quick Create", then follow the
"Build for inference" instructions at
[Build ONNX Runtime from source](https://onnxruntime.ai/docs/build/).

1. Install any Windows Updates

2. Install [Visual Studio 2022 Community Edition](https://aka.ms/vs/17/release/vs_community.exe)
   1. Select "Desktop development with C++"

3. Start "Microsoft Store" and install all updates (needed for [winget](https://learn.microsoft.com/en-us/windows/package-manager/winget/))

4. Start "PowerShell"

   1. Install [git](https://git-scm.com/install/windows)
       ```
       winget install --id Git.Git -e --source winget
       ```

   2. Install [cmake](https://cmake.org/download/)
       ```
       winget install -e --id Kitware.CMake
       ```

   3. Install Python
       ```
       winget install Python.Python.3.14
       ```

   4. Install [Java JDK 17 from Oracle](https://www.oracle.com/java/technologies/downloads/) (I don't know why OpenJDK doesn't work)

5. Start "Developer PowerShell for VS 2022"

   1. Clone the [onnxruntime repository](https://github.com/microsoft/onnxruntime)
       ```
       git clone --recursive https://github.com/Microsoft/onnxruntime.git
       cd onnxruntime
       git checkout v1.25.1
       git submodule update
       ```

   2. Start the build
       ```
       ./build.bat --config RelWithDebInfo --parallel --cmake_generator "Visual Studio 17 2022" --build_java --use_dml
       ```
6. The built `onnxruntime.jar` is at:
    ```
    onnxruntime\java\build\libs\onnxruntime.jar
    ```
