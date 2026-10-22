# GhostWire product hooks. Inherited from a device/common makefile via
# $(call inherit-product-if-exists, packages/apps/GhostWire/ghostwire.mk);
# inherit-product sets LOCAL_PATH to this directory.

PRODUCT_PACKAGES += \
    GhostWire \
    ghostwire_diag

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/privapp-permissions-ghostwire.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-ghostwire.xml
