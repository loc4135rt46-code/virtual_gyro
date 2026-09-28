// IHalProxyCallbackWrapper.h
// Rut gon tu tham khao AOSP that: .../fake_subhal/IHalProxyCallbackWrapper.h
// Ban goc ho tro ca V2_0 lan V2_1 (can convertV2_1.h de doi kieu du lieu).
// Thiet bi nay xac nhan dung dung 2.1 (qua nm -D symbol dump) nen bo hang
// V2_0 di, khoi phai keo them file convert khong co san.

#pragma once

#include "V2_1/SubHal.h"

namespace android {
namespace hardware {
namespace sensors {
namespace V2_1 {
namespace subhal {
namespace implementation {

using ::android::hardware::Return;
using ::android::hardware::hidl_vec;
using ::android::hardware::sensors::V2_1::Event;
using ::android::hardware::sensors::V2_1::SensorInfo;
using ::android::hardware::sensors::V2_1::implementation::IHalProxyCallback;
using ScopedWakelock = ::android::hardware::sensors::V2_0::implementation::ScopedWakelock;

class IHalProxyCallbackWrapperBase {
  public:
    virtual ~IHalProxyCallbackWrapperBase() {}
    virtual Return<void> onDynamicSensorsConnected(const hidl_vec<SensorInfo>& sensorInfos) = 0;
    virtual Return<void> onDynamicSensorsDisconnected(const hidl_vec<int32_t>& sensorHandles) = 0;
    virtual void postEvents(const std::vector<Event>& events, ScopedWakelock wakelock) = 0;
    virtual ScopedWakelock createScopedWakelock(bool lock) = 0;
};

class HalProxyCallbackWrapperV2_1 : public IHalProxyCallbackWrapperBase {
  public:
    explicit HalProxyCallbackWrapperV2_1(sp<IHalProxyCallback> callback)
        : mCallback(callback) {}

    Return<void> onDynamicSensorsConnected(const hidl_vec<SensorInfo>& sensorInfos) override {
        return mCallback->onDynamicSensorsConnected_2_1(sensorInfos);
    }

    Return<void> onDynamicSensorsDisconnected(const hidl_vec<int32_t>& sensorHandles) override {
        return mCallback->onDynamicSensorsDisconnected(sensorHandles);
    }

    void postEvents(const std::vector<Event>& events, ScopedWakelock wakelock) override {
        mCallback->postEvents(events, std::move(wakelock));
    }

    ScopedWakelock createScopedWakelock(bool lock) override {
        return mCallback->createScopedWakelock(lock);
    }

  private:
    sp<IHalProxyCallback> mCallback;
};

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_1
}  // namespace sensors
}  // namespace hardware
}  // namespace android
