# HiBreakBreak Pro 0.1.6 features beyond its controller (built from vendor/hibreakbreak-pro/controller).
# The controller, a9_eink_server, waveform key, and Bigme XRZ policy are supplied by
# the existing A17 modules; this makefile only adds the sleep and visual payload.
# Status-bar-edge, white-shade and light-QS overlays reference SystemUI-private resources by ID, which
# change between releases; they are ported as frameworks_base SystemUI resource patches instead.
PRODUCT_PACKAGES += \
    treble-overlay-Bigme-HiBreak_EEA \
    HiBreakBirdDoze \
    HiBreakProbe \
    hibreak-bootanimation \
    hibreak-bootanimation-rc \
    hibreak-initial-package-stopped-states \
    HiBreakSettingsDefaults \
    RefreshPrinceDefaults \
    RefreshPrinceLightShade

# Companion applications are image prebuilts; no post-boot defaults service is needed.
$(call inherit-product, vendor/hibreak/mods/mods.mk)
