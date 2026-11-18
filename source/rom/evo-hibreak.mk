# Evolution X (cnb, Android 17) GSI for the Bigme HiBreak Pro. Passed to device/phh/treble/generate.sh,
# which adds it to every treble_* product. Based on mytja/treble_evo evo.mk (A15).
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)
$(call inherit-product, vendor/lineage/config/BoardConfigSoong.mk)
$(call inherit-product, device/lineage/sepolicy/common/sepolicy.mk)

# HiBreak E-Ink daemon and accessibility service.
$(call inherit-product, vendor/hibreakbreak-pro/controller/a9_services.mk)
# HiBreakBreak sleep dream, static visual overlays, and Bigme hardware overlay.
$(call inherit-product, vendor/hibreak/hibreakbreak/hibreakbreak.mk)

PRODUCT_BRAND := google
PRODUCT_SYSTEM_BRAND := google
PRODUCT_MANUFACTURER := google
PRODUCT_SYSTEM_MANUFACTURER := google
PRODUCT_MODEL := Evolution X GSI

# GSI: no kernel, stock vendor
TARGET_NO_KERNEL_OVERRIDE := true
TARGET_NO_KERNEL_IMAGE := true
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := false
TARGET_USES_PREBUILT_VENDOR_SEPOLICY := true
TARGET_BOOT_ANIMATION_RES := 1080
TARGET_SUPPORTS_64_BIT_APPS := true

# The stock 4.19 kernel has no EROFS (CONFIG_EROFS_FS is not set): every APEX payload must be ext4,
# and the virtualization APEX (EROFS-only payload) can't be mounted, so leave AVF out.
PRODUCT_DEFAULT_APEX_PAYLOAD_TYPE := ext4
PRODUCT_AVF_ENABLED := false

EVO_BUILD_TYPE := Unofficial

# MediaTek GED KPI: libgui reports frame timestamps to /proc/ged so the GPU DVFS governor sees real
# frame load (the A15 build had this; without it the GPU stays at its lowest OPP).
$(call soong_config_set_bool,libgui,support_mtk_ged_kpi,true)

# The stock XRZ HWC treats empty client-target damage as unchanged. SurfaceFlinger
# flattens HiBreak frames into the client target and keeps this policy enabled.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.bigme.force_client_composition=true
