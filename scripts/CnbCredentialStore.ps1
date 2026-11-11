# Windows-only credential storage. Never log the returned token.
if (-not ('CampusCnbCredentialStore' -as [type])) {
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class CampusCnbCredentialStore {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    private struct Credential {
        public uint Flags; public uint Type;
        public string TargetName; public string Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize; public IntPtr CredentialBlob;
        public uint Persist; public uint AttributeCount; public IntPtr Attributes;
        public string TargetAlias; public string UserName;
    }
    [DllImport("advapi32.dll", EntryPoint="CredWriteW", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool CredWrite(ref Credential credential, uint flags);
    [DllImport("advapi32.dll", EntryPoint="CredReadW", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);
    [DllImport("advapi32.dll", EntryPoint="CredDeleteW", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool CredDelete(string target, uint type, uint flags);
    [DllImport("advapi32.dll")] private static extern void CredFree(IntPtr credential);
    private static void ValidateTarget(string target) {
        if (target != "CampusCompanion/CNB/campus-toolbox/campus-toolbox/repo-release" && !target.StartsWith("CampusCompanion/CNB/Test/",StringComparison.Ordinal))
            throw new ArgumentException("Credential target not allowed.");
    }
    public static void Write(string target, string token) {
        ValidateTarget(target);
        if (String.IsNullOrWhiteSpace(token) || Encoding.Unicode.GetByteCount(token)>2048)
            throw new ArgumentException("Invalid credential length.");
        IntPtr data=Marshal.StringToCoTaskMemUni(token);
        try {
            Credential value=new Credential(); value.Type=1; value.TargetName=target;
            value.UserName="cnb"; value.Comment="Campus Companion CNB release publishing";
            value.CredentialBlob=data; value.CredentialBlobSize=(uint)Encoding.Unicode.GetByteCount(token);
            value.Persist=2; // Current Windows user, this computer; no enterprise roaming.
            if (!CredWrite(ref value,0)) throw new InvalidOperationException("Windows credential write failed: "+Marshal.GetLastWin32Error());
        } finally { Marshal.ZeroFreeCoTaskMemUnicode(data); }
    }
    public static string Read(string target) {
        ValidateTarget(target); IntPtr data;
        if (!CredRead(target,1,0,out data)) {
            int code=Marshal.GetLastWin32Error();
            if(code==1168) return null;
            throw new InvalidOperationException("Windows credential read failed: "+code);
        }
        try {
            Credential value=(Credential)Marshal.PtrToStructure(data,typeof(Credential));
            if(value.CredentialBlobSize==0 || value.CredentialBlobSize>2048 || value.CredentialBlobSize%2!=0)
                throw new InvalidOperationException("Invalid stored credential length.");
            return Marshal.PtrToStringUni(value.CredentialBlob,(int)value.CredentialBlobSize/2);
        } finally { CredFree(data); }
    }
    public static void Remove(string target) {
        ValidateTarget(target);
        if(!CredDelete(target,1,0) && Marshal.GetLastWin32Error()!=1168)
            throw new InvalidOperationException("Windows credential removal failed.");
    }
}
"@
}
function Get-CampusCnbToken {
    return [CampusCnbCredentialStore]::Read('CampusCompanion/CNB/campus-toolbox/campus-toolbox/repo-release')
}
function Set-CampusCnbToken([string]$Token) {
    if($Token -cnotmatch '\A[A-Za-z0-9._~+/=-]{20,1024}\z'){throw 'Invalid token format.'}
    [CampusCnbCredentialStore]::Write('CampusCompanion/CNB/campus-toolbox/campus-toolbox/repo-release',$Token)
}
function Remove-CampusCnbToken {
    [CampusCnbCredentialStore]::Remove('CampusCompanion/CNB/campus-toolbox/campus-toolbox/repo-release')
}