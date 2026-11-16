# 面部几何实验室

依据《颜值打分 App 课程设计任务书》制作的 Android 原生项目。相册选图、相机拍照和实时检测共用同一套端侧人脸关键点及几何分析逻辑。

## 导入与运行

1. 解压后，在 Android Studio 中选择 **Open**，打开含有 `settings.gradle` 的 **FaceGeometryLab** 文件夹。
2. 在 **SDK Manager** 中安装 **Android SDK Platform 35**。Gradle JDK 选 **JDK 17**。首次同步要下载 Gradle、CameraX、ML Kit 等依赖。
3. 连接 USB 调试已开启的 Android 9 或更新版本手机，点击 **Run**。建议用带前置摄像头的真机演示。
4. 需要单独导出 APK 时，在项目根目录执行 `gradlew.bat assembleDebug`（Windows）或 `sh ./gradlew assembleDebug`（macOS、Linux）。产物为 `app/build/outputs/apk/debug/app-debug.apk`。

## 演示流程

| 操作 | 预期结果 |
| --- | --- |
| 相册选择单人正脸图 | 显示人脸框、468 个关键点、1–10 分及九项指标。可切换关键点显示。 |
| 打开相机 | 前置相机优先。画面中的单人脸触发实时检测和分数更新。 |
| 点击拍照 | 冻结当前拍摄结果并退出实时预览，显示人脸框、关键点及详细评分依据。 |
| 切换通用、男性、女性模板 | 对同一组几何实测值按所选权重重新计算。 |
| 输入无人脸图或含多张可检测人脸的图 | 清空旧结果并提示换图。 |
| 拒绝相机权限 | 提示授权方式，相册入口仍可用。 |

## 实现方法

- `MainActivity.java` 处理图片输入、相机生命周期、检测任务和结果界面。相册选图走系统选择器，无需申请读取整个相册的权限。实时检测丢弃积压帧，并限制检测提交频率。
- `PhotoView.java` 按照片缩放比例对齐绘制人脸框和关键点。
- `Geometry.java` 使用双眼外眼角构建坐标轴以抵消平面内旋转，用人脸或眼睛尺寸归一化。三庭以上额边界近似发际线，五眼以脸宽除以左右眼平均宽度估算。眉眼距、鼻梁区、唇形、对称性、下颌宽、眼型和面部外形均使用对应点位比例。
- 每项匹配度采用 `max(0, 1 − |实测值 − 参考值| / 容差)`，最终分数为 `1 + 9 × Σ(权重 × 匹配度) / Σ权重`。男性模板提高下颌及眉眼距权重，女性模板提高眼型、唇形及面部外形权重。模板由操作者手动选择。
- 任务书推荐 68/106 个关键点，本项目采用 ML Kit Face Mesh 提供的 468 点模型。评分参考值、容差及模板权重为透明的课程演示参数，尚未以 SCUT-FBP5500 等数据集训练或校准。对侧脸、遮挡、过小人脸及透视畸变，检测与比例测量可能失准。

## 隐私与实验边界

人脸推理、照片和分数均在设备上处理。应用无联网权限，拍照图像与关键点不写入本地文件，也不提供公开榜单。数据集中的颜值标签只是特定人群主观打分的统计结果，本项目输出则是规则匹配分。两者都不能衡量人的价值。课程演示使用取得同意的人像。项目范围为面部几何分析，数据集训练、疾病识别和性取向推断属于本项目范围之外。

验证时可在同一张照片上比较三种模板，并分别记录正脸、轻度转头、不同距离、无人脸及多人图像的输出。保留实测输入条件、是否出分、关键点是否正确贴合和观察到的异常。项目包不附实机演示截图。

## 技术参考

- [ML Kit Face Mesh Android 指南](https://developers.google.com/ml-kit/vision/face-mesh-detection/android)
- [CameraX 官方说明](https://developer.android.com/jetpack/androidx/releases/camera)
- [Android Gradle Plugin 8.7 兼容性](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
- [Gradle 发行包及 Wrapper 校验值](https://gradle.org/release-checksums/)
