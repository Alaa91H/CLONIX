#!/system/bin/sh
# ClonePilot KSU/Magisk installer (magic-mount only, no scripts at boot).
SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false
REPLACE=""

print_modname() {
  ui_print "*******************************"
  ui_print " ClonePilot - App Cloner        "
  ui_print " N-copy cloner, numbered badges "
  ui_print "*******************************"
}

on_install() {
  if [ ! -f "$MODPATH/system/priv-app/ClonePilot/ClonePilot.apk" ]; then
    ui_print "! Missing ClonePilot.apk"
    ui_print "! Put the built APK at:"
    ui_print "!   system/priv-app/ClonePilot/ClonePilot.apk"
    ui_print "! then re-flash this zip."
    abort "! APK missing"
  fi
  ui_print "- APK found, setting permissions..."
}

set_permissions() {
  set_perm_recursive "$MODPATH/system/priv-app" 0 0 0755 0644
  set_perm_recursive "$MODPATH/system/etc/permissions" 0 0 0755 0644
}
