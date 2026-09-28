// VirtualGyroSubHal.cpp
// Rut gon tu tham khao AOSP that: .../fake_subhal/SensorsSubHal.cpp

#include "VirtualGyroSubHal.h"

#include <android/log.h>

#include <cstdio>
#include <sstream>
#include <unistd.h>

#define LOG_TAG "VirtualGyroSubHal"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Ham export bat buoc - HalProxy cua samsung-multihal se tim symbol nay khi
// load thu vien .so nay (khai qua hals.conf). Chu ky va kieu tra ve phai
// khop 100% voi khai bao trong V2_1/SubHal.h.
::android::hardware::sensors::V2_1::implementation::ISensorsSubHal* sensorsHalGetSubHal_2_1(
        uint32_t* version) {
    static ::android::hardware::sensors::V2_1::subhal::implementation::VirtualGyroSubHal subHal;
    *version = SUB_HAL_2_1_VERSION;
    return &subHal;
}

namespace android {
namespace hardware {
namespace sensors {
namespace V2_1 {
namespace subhal {
namespace implementation {

VirtualGyroSubHal::VirtualGyroSubHal() : mCallback(nullptr) {
    // Handle bat dau tu 1 - HalProxy tu dung byte cao de phan biet sub-HAL,
    // sub-HAL chi can dam bao handle > 0 va khong trung nhau trong noi bo.
    constexpr int32_t kGyroHandle = 1;
    std::shared_ptr<VirtualGyroSensor> gyro =
            std::make_shared<VirtualGyroSensor>(kGyroHandle, this);
    mSensors[kGyroHandle] = gyro;
}

Return<void> VirtualGyroSubHal::getSensorsList_2_1(getSensorsList_2_1_cb _hidl_cb) {
    std::vector<SensorInfo> sensors;
    for (const auto& entry : mSensors) {
        sensors.push_back(entry.second->getSensorInfo());
    }
    _hidl_cb(sensors);
    return Void();
}

Return<Result> VirtualGyroSubHal::injectSensorData_2_1(const Event& /* event */) {
    // Khong ho tro data injection cho gyro ao nay.
    return Result::INVALID_OPERATION;
}

Return<Result> VirtualGyroSubHal::setOperationMode(OperationMode mode) {
    for (auto& entry : mSensors) {
        entry.second->setOperationMode(mode);
    }
    mCurrentOperationMode = mode;
    return Result::OK;
}

Return<Result> VirtualGyroSubHal::activate(int32_t sensorHandle, bool enabled) {
    auto it = mSensors.find(sensorHandle);
    if (it != mSensors.end()) {
        it->second->activate(enabled);
        return Result::OK;
    }
    return Result::BAD_VALUE;
}

Return<Result> VirtualGyroSubHal::batch(int32_t sensorHandle, int64_t samplingPeriodNs,
                                        int64_t /* maxReportLatencyNs */) {
    auto it = mSensors.find(sensorHandle);
    if (it != mSensors.end()) {
        it->second->batch(samplingPeriodNs);
        return Result::OK;
    }
    return Result::BAD_VALUE;
}

Return<Result> VirtualGyroSubHal::flush(int32_t sensorHandle) {
    auto it = mSensors.find(sensorHandle);
    if (it != mSensors.end()) {
        return it->second->flush();
    }
    return Result::BAD_VALUE;
}

Return<void> VirtualGyroSubHal::registerDirectChannel(const SharedMemInfo& /* mem */,
                                                      registerDirectChannel_cb _hidl_cb) {
    _hidl_cb(Result::INVALID_OPERATION, -1 /* channelHandle */);
    return Void();
}

Return<Result> VirtualGyroSubHal::unregisterDirectChannel(int32_t /* channelHandle */) {
    return Result::INVALID_OPERATION;
}

Return<void> VirtualGyroSubHal::configDirectReport(int32_t /* sensorHandle */,
                                                   int32_t /* channelHandle */,
                                                   RateLevel /* rate */,
                                                   configDirectReport_cb _hidl_cb) {
    _hidl_cb(Result::INVALID_OPERATION, 0 /* reportToken */);
    return Void();
}

Return<void> VirtualGyroSubHal::debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) {
    if (fd.getNativeHandle() == nullptr || fd->numFds < 1) {
        ALOGE("debug(): thieu fd de ghi");
        return Void();
    }

    FILE* out = fdopen(dup(fd->data[0]), "w");
    if (out == nullptr) {
        return Void();
    }

    if (args.size() != 0) {
        fprintf(out, "VirtualGyroSubHal khong ho tro tham so debug, bo qua.\n");
    }

    std::ostringstream stream;
    stream << "VirtualGyroSubHal - cam bien:" << std::endl;
    for (const auto& entry : mSensors) {
        SensorInfo info = entry.second->getSensorInfo();
        stream << "  " << info.name << " (handle=" << info.sensorHandle
               << ", minDelay=" << info.minDelay << "us)" << std::endl;
    }

    fprintf(out, "%s", stream.str().c_str());
    fclose(out);
    return Void();
}

Return<Result> VirtualGyroSubHal::initialize(const sp<IHalProxyCallback>& halProxyCallback) {
    mCallback = std::make_unique<HalProxyCallbackWrapperV2_1>(halProxyCallback);
    setOperationMode(OperationMode::NORMAL);
    return Result::OK;
}

void VirtualGyroSubHal::postEvents(const std::vector<Event>& events, bool wakeup) {
    if (mCallback == nullptr) {
        return;
    }
    ScopedWakelock wakelock = mCallback->createScopedWakelock(wakeup);
    mCallback->postEvents(events, std::move(wakelock));
}

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_1
}  // namespace sensors
}  // namespace hardware
}  // namespace android
