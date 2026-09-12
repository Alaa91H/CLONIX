# DualMessenger for Evolution-X (cnb)

نمط Samsung Dual Messenger لكن **بتعدد نسخ N** وبأقل موارد.

## الفكرة
- نفس الـAPK مشترك، كل نسخة = `userId` مخفي مختلف.
- النسخة 1 = Managed Profile `DualMessenger` (concurrent + إشعارات فورية بدون باتش).
- النسخ 2..8 = Secondary users `EvoClone_N` مخفيين + `startUserInBackground` + `BootReceiver`.
- لا تغيير packageName، لا إعادة توقيع، لا Virtualization. التحديث مرة واحدة، التخزين بيانات فقط `/data/user/<id>/`.

## المكونات
- `src/.../CloneManager.java` : إنشاء slots + installExistingAsUser + launch + separate contacts
- `CloneDatabase.java` : mapping pkg -> N clones
- `MainActivity.java` : UI سامسونج + بحث + long-press لإدارة النسخ
- `DualBadgeUtil.java` : بادج برتقالي مرقم (1..8) بدل شنطة Work
- `CloneLauncherTrampoline.java` : فتح النسخة من shortcut بدون باتش Launcher
- `BootReceiver` + `PackageCleanupReceiver` : استمرارية + تنظيف مثل سامسونج
- `settings_integration/` : دخول من EvoX Settings عبر intent (بدون تكرار كود)
- `launcher_patch/README` : باتش اختياري للدرج والإشعارات (UX فقط)
- `INTEGRATION.mk` : سطران في common.mk + overlay + أوامر اختبار

## الدمج
```
PRODUCT_PACKAGES += DualMessenger
```

## الحدود
- حد افتراضي 8 نسخ/تطبيق، 8 slots إجمالي (قابل للتغيير في CloneManager).
- التطبيقات singleUser والسيستم الداخلية مستبعدة تلقائياً.
- مسح الأصل يمسح النسخ (مثل سامسونج).
