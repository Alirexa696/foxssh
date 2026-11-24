; Inno Setup: نصب‌کنندهٔ Fox VPN برای ویندوز (ساخته‌شده توسط GitHub Actions)
#define AppVer GetEnv("FOX_VERSION")
#if AppVer == ""
  #define AppVer "1.1.0"
#endif

[Setup]
AppId={{6E1B2C54-5C3E-4C8E-9F46-0F0A5F0B7001}
AppName=Fox VPN
AppVersion={#AppVer}
AppPublisher=Fox VPN
DefaultDirName={autopf}\FoxVPN
DefaultGroupName=Fox VPN
UninstallDisplayIcon={app}\FoxVPN.exe
SetupIconFile=foxvpn.ico
OutputDir=..\build
OutputBaseFilename=FoxVPN-Setup-{#AppVer}
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "..\build\dist\FoxVPN\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Fox VPN"; Filename: "{app}\FoxVPN.exe"
Name: "{autodesktop}\Fox VPN"; Filename: "{app}\FoxVPN.exe"; Tasks: desktopicon

; همیشه با دسترسی Administrator اجرا شود (برای ساخت تونل سیستم)
[Registry]
Root: HKLM; Subkey: "SOFTWARE\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers"; ValueType: string; ValueName: "{app}\FoxVPN.exe"; ValueData: "~ RUNASADMIN"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\FoxVPN.exe"; Description: "Launch Fox VPN"; Flags: nowait postinstall skipifsilent runascurrentuser

[UninstallRun]
Filename: "taskkill"; Parameters: "/F /IM FoxVPN.exe"; Flags: runhidden; RunOnceId: "KillFox"
