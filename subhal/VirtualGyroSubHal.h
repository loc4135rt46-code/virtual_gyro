// VirtualGyroSubHal.h
// Rut gon tu tham khao AOSP that: .../fake_subhal/SensorsSubHal.h
// Bo phan template V2_0/V2_1 (chi giu 2.1), bo dynamic sensor, bo direct
// channel, bo data injection - thiet bi nay chi can dung 1 GYROSCOPE.

#pragma once

#include "IHalProxyCallbackWrapper.h"
#include "Sensor.h"

#include <map>
#include <memory>
#include <string>

namespace android {
namespace hardware {
namespace sensors {
namespace V2_1 {
namespace subhal {
namespace implementation {

using ::android::hardware::hidl_handle;
using ::android::hardware::hidl_string;
using ::android::hardware::Void;
using ::android::hardware::sensors::V1_0::RateLevel;
using ::android::hardware::sensors::V1_0::SharedMemInfo;
using ::android::hardware::sensors::V2_1::implementation::IHalProxyCallback;
using ::android::hardware::sensors::V2_1::implementation::ISensorsSubHal;

class VirtualGyroSubHal : public ISensorsSubHal, public ISensorsEventCallback {
  public:
    VirtualGyroSubHal();

    // Tu ISensorsSubHal (V2_1/SubHal.h)
    Return<void> debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) override;
    const std::string getName() override { return "VirtualGyroSubHal"; }
    Return<Result> initialize(const sp<IHalProxyCallback>& halProxyCallback) override;

    // Tu ISensors 2.1 (+ ke thua 2.0/1.0)
    Return<void> getSensorsList_2_1(getSensorsList_2_1_cb _hidl_cb) override;
    Return<Result> injectSensorData_2_1(const Event& event) override;
    Return<Result> setOperationMode(OperationMode mode) override;
    Return<Result> activate(int32_t sensorHandle, bool enabled) override;
    Return<Result> batch(int32_t sensorHandle, int64_t samplingPeriodNs,
                          int64_t maxReportLatencyNs) override;
    Return<Result> flush(int32_t sensorHandle) override;
    Return<void> registerDirectChannel(const SharedMemInfo& mem,
                                       registerDirectChannel_cb _hidl_cb) override;
    Return<Result> unregisterDirectChannel(int32_t channelHandle) override;
    Return<void> configDirectReport(int32_t sensorHandle, int32_t channelHandle, RateLevel rate,
                                    configDirectReport_cb _hidl_cb) override;

    // Tu ISensorsEventCallback (Sensor.h) - Sensor goi nguoc len day khi co du lieu moi
    void postEvents(const std::vector<Event>& events, bool wakeup) override;

  private:
    std::map<int32_t, std::shared_ptr<Sensor>> mSensors;
    std::unique_ptr<IHalProxyCallbackWrapperBase> mCallback;
    OperationMode mCurrentOperationMode = OperationMode::NORMAL;
};

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_1
}  // namespace sensors
}  // namespace hardware
}  // namespace android
