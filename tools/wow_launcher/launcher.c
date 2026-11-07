#include <windows.h>
#include <wincrypt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/*
 * Wow-Launcher-ARM64.exe
 * Native ARM64 Windows wrapper for World of Warcraft (CN Region).
 * 
 * Includes comprehensive PE import table scanner, translation layer diagnostics,
 * x64 environment probing, and Wine stderr redirection.
 */

static const wchar_t* const TOKEN_FILE_PATH     = L"E:\\bnet_token.tmp";
static const wchar_t* const LOG_DIR_PATH        = L"G:\\_forever_\\Logs";
static const wchar_t* const LOG_FILE_PATH       = L"G:\\_forever_\\Logs\\launcher_wrapper.log";
static const wchar_t* const WINE_DEBUG_LOG_PATH = L"G:\\_forever_\\Logs\\wine_debug.log";
static const wchar_t* const PROBE_EXE_PATH      = L"G:\\_forever_\\x64_probe.exe";
static const wchar_t* const GAME_X64_EXE_PATH   = L"G:\\_forever_\\Wow.exe";
static const wchar_t* const GAME_ARM64_EXE_PATH = L"G:\\_forever_\\Wow-ARM64.exe";
static const wchar_t* const X64_FLAG_PATH       = L"G:\\_forever_\\use_x64.flag";
static const wchar_t* const GAME_WORKING_DIR    = L"G:\\_forever_";
static const wchar_t* const GAME_X64_CMD_LINE   = L"\"G:\\_forever_\\Wow.exe\" -d3d11 -launcherlogin -uid wowf";
static const wchar_t* const GAME_ARM64_CMD_LINE = L"\"G:\\_forever_\\Wow-ARM64.exe\" -d3d11 -launcherlogin -uid wowf";
static const wchar_t* const REG_KEY_PATH        = L"Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\WoW";
static const wchar_t* const REG_BNA_KEY_PATH    = L"Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\BNA";

static const char* const PLACEHOLDER_ACCOUNT    = "wowf_cn_user@battlenet.com.cn";

static const BYTE ENTROPY[16] = {
    0xC8, 0x76, 0xF4, 0xAE, 0x4C, 0x95, 0x2E, 0xFE,
    0xF2, 0xFA, 0x0F, 0x54, 0x19, 0xC0, 0x9C, 0x43
};

static void LogStep(const char *stepName, DWORD win32ErrorCode) {
    CreateDirectoryW(LOG_DIR_PATH, NULL);
    FILE *fp = _wfopen(LOG_FILE_PATH, L"a");
    if (!fp) return;

    time_t now = time(NULL);
    struct tm *t = localtime(&now);
    char timeBuf[64] = {0};
    if (t) {
        strftime(timeBuf, sizeof(timeBuf), "%Y-%m-%d %H:%M:%S", t);
    } else {
        snprintf(timeBuf, sizeof(timeBuf), "%ld", (long)now);
    }

    fprintf(fp, "[%s] [%s] win32_err=%lu\n", timeBuf, stepName, win32ErrorCode);
    fflush(fp);
    fclose(fp);
}

static void LogExitCode(const char *tag, DWORD exitCode) {
    const char *desc = "";
    if (exitCode == 0xC0000135) desc = " [STATUS_DLL_NOT_FOUND]";
    else if (exitCode == 0xC0000142) desc = " [STATUS_DLL_INIT_FAILED]";
    else if (exitCode == 0xC000007B) desc = " [STATUS_INVALID_IMAGE_FORMAT]";
    else if (exitCode == 0xC0000005) desc = " [STATUS_ACCESS_VIOLATION]";
    else if (exitCode == 0) desc = " [STATUS_SUCCESS]";

    FILE *fp = _wfopen(LOG_FILE_PATH, L"a");
    if (!fp) return;
    time_t now = time(NULL);
    struct tm *t = localtime(&now);
    char timeBuf[64] = {0};
    if (t) strftime(timeBuf, sizeof(timeBuf), "%Y-%m-%d %H:%M:%S", t);
    fprintf(fp, "[%s] [%s] exit_code=%lu (0x%08lX)%s\n", timeBuf, tag, (unsigned long)exitCode, (unsigned long)exitCode, desc);
    fflush(fp);
    fclose(fp);
}

static void LogRaw(const char *msg) {
    CreateDirectoryW(LOG_DIR_PATH, NULL);
    FILE *fp = _wfopen(LOG_FILE_PATH, L"a");
    if (!fp) return;
    time_t now = time(NULL);
    struct tm *t = localtime(&now);
    char timeBuf[64] = {0};
    if (t) strftime(timeBuf, sizeof(timeBuf), "%Y-%m-%d %H:%M:%S", t);
    fprintf(fp, "[%s] %s\n", timeBuf, msg);
    fflush(fp);
    fclose(fp);
}

static void TrimString(char *s) {
    int len = (int)strlen(s);
    while (len > 0 && (s[len - 1] == '\r' || s[len - 1] == '\n' || s[len - 1] == ' ' || s[len - 1] == '\t')) {
        s[--len] = '\0';
    }
}

typedef BOOL (WINAPI *CryptProtectData_fn)(
    DATA_BLOB *pDataIn,
    LPCWSTR szDataDescr,
    DATA_BLOB *pOptionalEntropy,
    PVOID pvReserved,
    CRYPTPROTECT_PROMPTSTRUCT *pPromptStruct,
    DWORD dwFlags,
    DATA_BLOB *pDataOut
);

static BOOL ProtectDataBuffer(const char *plainText, DATA_BLOB *pOut) {
    HMODULE hCrypt = LoadLibraryW(L"crypt32.dll");
    if (!hCrypt) {
        hCrypt = LoadLibraryW(L"C:\\windows\\system32\\crypt32.dll");
    }
    if (!hCrypt) {
        LogRaw("[WARN] crypt32.dll could not be loaded for CryptProtectData");
        return FALSE;
    }

    CryptProtectData_fn pCryptProtectData = (CryptProtectData_fn)GetProcAddress(hCrypt, "CryptProtectData");
    if (!pCryptProtectData) {
        LogRaw("[WARN] GetProcAddress('CryptProtectData') failed");
        return FALSE;
    }

    DATA_BLOB in;
    DATA_BLOB entropy;
    in.pbData = (BYTE *)plainText;
    in.cbData = (DWORD)strlen(plainText);
    entropy.pbData = (BYTE *)ENTROPY;
    entropy.cbData = sizeof(ENTROPY);
    return pCryptProtectData(&in, NULL, &entropy, NULL, NULL, 0, pOut);
}

static void ShredAndDeleteTokenFile(void) {
    FILE *fp = _wfopen(TOKEN_FILE_PATH, L"rb+");
    if (fp) {
        fseek(fp, 0, SEEK_END);
        long size = ftell(fp);
        if (size > 0) {
            fseek(fp, 0, SEEK_SET);
            BYTE zeroBuf[512] = {0};
            long remaining = size;
            while (remaining > 0) {
                size_t toWrite = remaining > (long)sizeof(zeroBuf) ? sizeof(zeroBuf) : (size_t)remaining;
                fwrite(zeroBuf, 1, toWrite, fp);
                remaining -= (long)toWrite;
            }
            fflush(fp);
        }
        fclose(fp);
    }
    DeleteFileW(TOKEN_FILE_PATH);
}

/* ========================================================================= */
/* PE Parsing and Import Table Scanner                                      */
/* ========================================================================= */

static WORD GetPEFileMachine(const wchar_t *path) {
    HANDLE hFile = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hFile == INVALID_HANDLE_VALUE) return 0;

    IMAGE_DOS_HEADER dos;
    DWORD bytesRead = 0;
    if (!ReadFile(hFile, &dos, sizeof(dos), &bytesRead, NULL) || bytesRead != sizeof(dos) || dos.e_magic != IMAGE_DOS_SIGNATURE) {
        CloseHandle(hFile);
        return 0;
    }

    if (SetFilePointer(hFile, dos.e_lfanew, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER) {
        CloseHandle(hFile);
        return 0;
    }

    DWORD ntSig = 0;
    if (!ReadFile(hFile, &ntSig, sizeof(ntSig), &bytesRead, NULL) || bytesRead != sizeof(ntSig) || ntSig != IMAGE_NT_SIGNATURE) {
        CloseHandle(hFile);
        return 0;
    }

    IMAGE_FILE_HEADER fileHeader;
    if (!ReadFile(hFile, &fileHeader, sizeof(fileHeader), &bytesRead, NULL) || bytesRead != sizeof(fileHeader)) {
        CloseHandle(hFile);
        return 0;
    }

    CloseHandle(hFile);
    return fileHeader.Machine;
}

static BOOL SearchDLL(const wchar_t *dllNameW, wchar_t *outFoundPath, size_t outSize, WORD *outMachine) {
    static const wchar_t *searchDirs[] = {
        L"G:\\_forever_\\",
        L"G:\\_forever_\\D3D12\\",
        L"C:\\windows\\system32\\"
    };

    for (size_t i = 0; i < sizeof(searchDirs) / sizeof(searchDirs[0]); i++) {
        wchar_t candidate[512] = {0};
        _snwprintf(candidate, sizeof(candidate) / sizeof(wchar_t) - 1, L"%ls%ls", searchDirs[i], dllNameW);
        DWORD attr = GetFileAttributesW(candidate);
        if (attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY)) {
            if (outFoundPath) {
                wcsncpy(outFoundPath, candidate, outSize - 1);
            }
            if (outMachine) {
                *outMachine = GetPEFileMachine(candidate);
            }
            return TRUE;
        }
    }
    return FALSE;
}

static char s_scannedModules[64][64];
static int s_scannedCount = 0;

static BOOL HasScanned(const char *name) {
    for (int i = 0; i < s_scannedCount; i++) {
        if (_stricmp(s_scannedModules[i], name) == 0) return TRUE;
    }
    return FALSE;
}

static void MarkScanned(const char *name) {
    if (s_scannedCount < 64) {
        strncpy(s_scannedModules[s_scannedCount], name, sizeof(s_scannedModules[0]) - 1);
        s_scannedCount++;
    }
}

static const wchar_t *s_companionDlls[] = {
    L"G:\\_forever_\\wow_loader.dll",
    L"G:\\_forever_\\libxell.dll",
    L"G:\\_forever_\\GFSDK_Aftermath_Lib.x64.dll",
    L"G:\\_forever_\\BlizzardError.exe"
};

static DWORD RvaToOffset(DWORD rva, IMAGE_SECTION_HEADER *sections, WORD numSections) {
    if (rva == 0) return 0;
    for (WORD i = 0; i < numSections; i++) {
        DWORD secVa = sections[i].VirtualAddress;
        DWORD secSize = sections[i].Misc.VirtualSize;
        DWORD rawSize = sections[i].SizeOfRawData;
        DWORD extent = secSize > rawSize ? secSize : rawSize;
        if (extent == 0) extent = rawSize;
        if (rva >= secVa && rva < secVa + extent) {
            return sections[i].PointerToRawData + (rva - secVa);
        }
    }
    return 0;
}

#ifndef IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT
#define IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT 13
#endif

static void LogPEHeaderDetails(const wchar_t *path, const char *label) {
    HANDLE hFile = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hFile == INVALID_HANDLE_VALUE) return;

    IMAGE_DOS_HEADER dos;
    DWORD bytesRead = 0;
    if (!ReadFile(hFile, &dos, sizeof(dos), &bytesRead, NULL) || bytesRead != sizeof(dos) || dos.e_magic != IMAGE_DOS_SIGNATURE) {
        CloseHandle(hFile);
        return;
    }

    if (dos.e_lfanew <= 0 || SetFilePointer(hFile, dos.e_lfanew, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER) {
        CloseHandle(hFile);
        return;
    }

    DWORD ntSig = 0;
    if (!ReadFile(hFile, &ntSig, sizeof(ntSig), &bytesRead, NULL) || bytesRead != sizeof(ntSig) || ntSig != IMAGE_NT_SIGNATURE) {
        CloseHandle(hFile);
        return;
    }

    IMAGE_FILE_HEADER fileHeader;
    if (!ReadFile(hFile, &fileHeader, sizeof(fileHeader), &bytesRead, NULL) || bytesRead != sizeof(fileHeader)) {
        CloseHandle(hFile);
        return;
    }

    if (fileHeader.Machine == IMAGE_FILE_MACHINE_AMD64) {
        IMAGE_OPTIONAL_HEADER64 opt;
        if (ReadFile(hFile, &opt, sizeof(opt), &bytesRead, NULL) && bytesRead == sizeof(opt)) {
            char buf[512] = {0};
            snprintf(buf, sizeof(buf), "[PE_HEADER] %s: Machine=0x%04X EntryPoint=0x%08lX ImageBase=0x%llX StackReserve=0x%llX StackCommit=0x%llX",
                     label, fileHeader.Machine, (unsigned long)opt.AddressOfEntryPoint,
                     (unsigned long long)opt.ImageBase,
                     (unsigned long long)opt.SizeOfStackReserve,
                     (unsigned long long)opt.SizeOfStackCommit);
            LogRaw(buf);

            DWORD tlsRva = opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_TLS].VirtualAddress;
            DWORD tlsSize = opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_TLS].Size;
            DWORD delayRva = opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT].VirtualAddress;
            DWORD delaySize = opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT].Size;
            DWORD secRva = opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_SECURITY].VirtualAddress;
            DWORD secSize = opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_SECURITY].Size;

            char dirBuf[512] = {0};
            snprintf(dirBuf, sizeof(dirBuf), "[PE_HEADER] %s: TLS=(RVA 0x%08lX, Size %lu) DelayImport=(RVA 0x%08lX, Size %lu) Security=(RVA 0x%08lX, Size %lu)",
                     label, (unsigned long)tlsRva, (unsigned long)tlsSize,
                     (unsigned long)delayRva, (unsigned long)delaySize,
                     (unsigned long)secRva, (unsigned long)secSize);
            LogRaw(dirBuf);
        }
    }
    CloseHandle(hFile);
}

static void ScanPEImports(const wchar_t *filePath, const char *parentLabel, int depth) {
    if (depth > 3 || s_scannedCount >= 64) return;

    HANDLE hFile = CreateFileW(filePath, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hFile == INVALID_HANDLE_VALUE) return;

    DWORD fileSize = GetFileSize(hFile, NULL);
    if (fileSize < sizeof(IMAGE_DOS_HEADER) + sizeof(IMAGE_NT_HEADERS64)) {
        CloseHandle(hFile);
        return;
    }

    IMAGE_DOS_HEADER dos;
    DWORD bytesRead = 0;
    if (!ReadFile(hFile, &dos, sizeof(dos), &bytesRead, NULL) || bytesRead != sizeof(dos) || dos.e_magic != IMAGE_DOS_SIGNATURE) {
        CloseHandle(hFile);
        return;
    }

    if (dos.e_lfanew <= 0 || (DWORD)dos.e_lfanew + sizeof(IMAGE_NT_HEADERS64) > fileSize) {
        CloseHandle(hFile);
        return;
    }

    if (SetFilePointer(hFile, dos.e_lfanew, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER) {
        CloseHandle(hFile);
        return;
    }

    DWORD ntSig = 0;
    if (!ReadFile(hFile, &ntSig, sizeof(ntSig), &bytesRead, NULL) || bytesRead != sizeof(ntSig) || ntSig != IMAGE_NT_SIGNATURE) {
        CloseHandle(hFile);
        return;
    }

    IMAGE_FILE_HEADER fileHeader;
    if (!ReadFile(hFile, &fileHeader, sizeof(fileHeader), &bytesRead, NULL) || bytesRead != sizeof(fileHeader)) {
        CloseHandle(hFile);
        return;
    }

    DWORD importRva = 0;
    DWORD importSize = 0;
    DWORD delayRva = 0;
    DWORD delaySize = 0;

    if (fileHeader.SizeOfOptionalHeader >= sizeof(IMAGE_OPTIONAL_HEADER64)) {
        IMAGE_OPTIONAL_HEADER64 opt64;
        if (ReadFile(hFile, &opt64, sizeof(opt64), &bytesRead, NULL) && bytesRead == sizeof(opt64)) {
            if (opt64.NumberOfRvaAndSizes > IMAGE_DIRECTORY_ENTRY_IMPORT) {
                importRva = opt64.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].VirtualAddress;
                importSize = opt64.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].Size;
            }
            if (opt64.NumberOfRvaAndSizes > IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT) {
                delayRva = opt64.DataDirectory[IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT].VirtualAddress;
                delaySize = opt64.DataDirectory[IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT].Size;
            }
        }
    } else if (fileHeader.SizeOfOptionalHeader >= sizeof(IMAGE_OPTIONAL_HEADER32)) {
        IMAGE_OPTIONAL_HEADER32 opt32;
        if (ReadFile(hFile, &opt32, sizeof(opt32), &bytesRead, NULL) && bytesRead == sizeof(opt32)) {
            if (opt32.NumberOfRvaAndSizes > IMAGE_DIRECTORY_ENTRY_IMPORT) {
                importRva = opt32.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].VirtualAddress;
                importSize = opt32.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].Size;
            }
            if (opt32.NumberOfRvaAndSizes > IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT) {
                delayRva = opt32.DataDirectory[IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT].VirtualAddress;
                delaySize = opt32.DataDirectory[IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT].Size;
            }
        }
    }

    if (importRva == 0 && delayRva == 0) {
        CloseHandle(hFile);
        return;
    }

    // Read section headers
    DWORD secPos = dos.e_lfanew + sizeof(DWORD) + sizeof(IMAGE_FILE_HEADER) + fileHeader.SizeOfOptionalHeader;
    if (SetFilePointer(hFile, secPos, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER) {
        CloseHandle(hFile);
        return;
    }

    WORD numSections = fileHeader.NumberOfSections;
    if (numSections > 96) numSections = 96;
    IMAGE_SECTION_HEADER sections[96];
    DWORD secBytes = numSections * sizeof(IMAGE_SECTION_HEADER);
    if (!ReadFile(hFile, sections, secBytes, &bytesRead, NULL) || bytesRead != secBytes) {
        CloseHandle(hFile);
        return;
    }

    char recursePaths[16][MAX_PATH];
    char recurseNames[16][64];
    int recurseCount = 0;

    // 1. Scan Standard Imports
    if (importRva != 0 && importSize != 0) {
        DWORD importOffset = RvaToOffset(importRva, sections, numSections);
        if (importOffset != 0 && importOffset < fileSize) {
            for (DWORD i = 0; i < 128; i++) {
                DWORD descOffset = importOffset + i * sizeof(IMAGE_IMPORT_DESCRIPTOR);
                if (descOffset + sizeof(IMAGE_IMPORT_DESCRIPTOR) > fileSize) break;

                if (SetFilePointer(hFile, descOffset, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER) break;
                IMAGE_IMPORT_DESCRIPTOR desc;
                if (!ReadFile(hFile, &desc, sizeof(desc), &bytesRead, NULL) || bytesRead != sizeof(desc)) break;
                if (desc.Name == 0 && desc.FirstThunk == 0 && desc.OriginalFirstThunk == 0) break;
                if (desc.Name == 0) continue;

                DWORD nameOffset = RvaToOffset(desc.Name, sections, numSections);
                if (nameOffset == 0 || nameOffset >= fileSize) continue;

                if (SetFilePointer(hFile, nameOffset, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER) continue;
                char dllName[64] = {0};
                ReadFile(hFile, dllName, sizeof(dllName) - 1, &bytesRead, NULL);
                dllName[sizeof(dllName) - 1] = '\0';

                wchar_t dllNameW[64] = {0};
                MultiByteToWideChar(CP_ACP, 0, dllName, -1, dllNameW, sizeof(dllNameW) / sizeof(wchar_t) - 1);

                wchar_t foundPath[512] = {0};
                WORD machine = 0;
                BOOL found = SearchDLL(dllNameW, foundPath, sizeof(foundPath) / sizeof(wchar_t), &machine);

                char logLine[256] = {0};
                if (found) {
                    snprintf(logLine, sizeof(logLine), "[IMPORT_FOUND] parent=%s dll=%s machine=0x%04X", parentLabel, dllName, machine);
                    LogRaw(logLine);

                    // If found in G:\_forever_\, queue for recursive scan
                    if (depth < 3 && wcsstr(foundPath, L"G:\\_forever_\\") != NULL && !HasScanned(dllName)) {
                        MarkScanned(dllName);
                        if (recurseCount < 16) {
                            WideCharToMultiByte(CP_ACP, 0, foundPath, -1, recursePaths[recurseCount], MAX_PATH - 1, NULL, NULL);
                            strncpy(recurseNames[recurseCount], dllName, sizeof(recurseNames[0]) - 1);
                            recurseCount++;
                        }
                    }
                } else {
                    snprintf(logLine, sizeof(logLine), "[IMPORT_NOT_FOUND] parent=%s dll=%s", parentLabel, dllName);
                    LogRaw(logLine);
                }
            }
        }
    }

    // 2. Scan Delay-Load Imports
    if (delayRva != 0 && delaySize != 0) {
        DWORD delayOffset = RvaToOffset(delayRva, sections, numSections);
        if (delayOffset != 0 && delayOffset < fileSize) {
            for (DWORD i = 0; i < 128; i++) {
                DWORD descOffset = delayOffset + i * sizeof(IMAGE_DELAYLOAD_DESCRIPTOR);
                if (descOffset + sizeof(IMAGE_DELAYLOAD_DESCRIPTOR) > fileSize) break;

                if (SetFilePointer(hFile, descOffset, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER) break;
                IMAGE_DELAYLOAD_DESCRIPTOR delayDesc;
                if (!ReadFile(hFile, &delayDesc, sizeof(delayDesc), &bytesRead, NULL) || bytesRead != sizeof(delayDesc)) break;
                if (delayDesc.DllNameRVA == 0) break;

                DWORD nameOffset = RvaToOffset(delayDesc.DllNameRVA, sections, numSections);
                if (nameOffset == 0 || nameOffset >= fileSize) continue;

                if (SetFilePointer(hFile, nameOffset, NULL, FILE_BEGIN) == INVALID_SET_FILE_POINTER) continue;
                char dllName[64] = {0};
                ReadFile(hFile, dllName, sizeof(dllName) - 1, &bytesRead, NULL);
                dllName[sizeof(dllName) - 1] = '\0';

                wchar_t dllNameW[64] = {0};
                MultiByteToWideChar(CP_ACP, 0, dllName, -1, dllNameW, sizeof(dllNameW) / sizeof(wchar_t) - 1);

                wchar_t foundPath[512] = {0};
                WORD machine = 0;
                BOOL found = SearchDLL(dllNameW, foundPath, sizeof(foundPath) / sizeof(wchar_t), &machine);

                char logLine[256] = {0};
                if (found) {
                    snprintf(logLine, sizeof(logLine), "[DELAY_IMPORT_FOUND] parent=%s dll=%s machine=0x%04X", parentLabel, dllName, machine);
                    LogRaw(logLine);

                    // If found in G:\_forever_\, queue for recursive scan
                    if (depth < 3 && wcsstr(foundPath, L"G:\\_forever_\\") != NULL && !HasScanned(dllName)) {
                        MarkScanned(dllName);
                        if (recurseCount < 16) {
                            WideCharToMultiByte(CP_ACP, 0, foundPath, -1, recursePaths[recurseCount], MAX_PATH - 1, NULL, NULL);
                            strncpy(recurseNames[recurseCount], dllName, sizeof(recurseNames[0]) - 1);
                            recurseCount++;
                        }
                    }
                } else {
                    snprintf(logLine, sizeof(logLine), "[DELAY_IMPORT_NOT_FOUND] parent=%s dll=%s", parentLabel, dllName);
                    LogRaw(logLine);
                }
            }
        }
    }

    CloseHandle(hFile);

    for (int r = 0; r < recurseCount; r++) {
        wchar_t rPathW[MAX_PATH] = {0};
        MultiByteToWideChar(CP_ACP, 0, recursePaths[r], -1, rPathW, MAX_PATH - 1);
        ScanPEImports(rPathW, recurseNames[r], depth + 1);
    }
}

/* ========================================================================= */
/* System32 and Environment Diagnostics                                      */
/* ========================================================================= */

static LONGLONG GetFileSizeBytes(const wchar_t *path) {
    WIN32_FILE_ATTRIBUTE_DATA fad;
    if (!GetFileAttributesExW(path, GetFileExInfoStandard, &fad)) return -1;
    LARGE_INTEGER li;
    li.LowPart = fad.nFileSizeLow;
    li.HighPart = fad.nFileSizeHigh;
    return li.QuadPart;
}

static void EnsureSystem32FexCoreDlls(void) {
    static const wchar_t *fexDlls[] = {
        L"libarm64ecfex.dll",
        L"libwow64fex.dll"
    };

    for (size_t i = 0; i < sizeof(fexDlls) / sizeof(fexDlls[0]); i++) {
        wchar_t srcPath[MAX_PATH] = {0};
        wchar_t dstPath[MAX_PATH] = {0};
        _snwprintf(srcPath, MAX_PATH - 1, L"G:\\_forever_\\%ls", fexDlls[i]);
        _snwprintf(dstPath, MAX_PATH - 1, L"C:\\windows\\system32\\%ls", fexDlls[i]);

        DWORD srcAttr = GetFileAttributesW(srcPath);
        if (srcAttr != INVALID_FILE_ATTRIBUTES && !(srcAttr & FILE_ATTRIBUTE_DIRECTORY)) {
            LONGLONG srcSize = GetFileSizeBytes(srcPath);
            LONGLONG dstSize = GetFileSizeBytes(dstPath);

            if (dstSize < 0 || dstSize != srcSize) {
                BOOL copied = CopyFileW(srcPath, dstPath, FALSE);
                char logBuf[256] = {0};
                snprintf(logBuf, sizeof(logBuf), "[SYS32_INJECT] Copied %ls (%lld bytes) -> %ls (old=%lld): success=%d win32_err=%lu",
                         srcPath, (long long)srcSize, dstPath, (long long)dstSize, copied, (unsigned long)GetLastError());
                LogRaw(logBuf);
            } else {
                char logBuf[256] = {0};
                snprintf(logBuf, sizeof(logBuf), "[SYS32_INJECT] %ls already present in system32 (%lld bytes)",
                         dstPath, (long long)dstSize);
                LogRaw(logBuf);
            }
        } else {
            char logBuf[256] = {0};
            snprintf(logBuf, sizeof(logBuf), "[SYS32_INJECT] Source %ls not found in G:\\_forever_\\", srcPath);
            LogRaw(logBuf);
        }
    }
}

static void RunSystem32AndEnvDiagnostics(void) {
    // Proactively deploy FEXCore translation DLLs from G:\_forever_\ into C:\windows\system32\ inside container
    EnsureSystem32FexCoreDlls();

    LogRaw("=== SYSTEM32 TRANSLATION & GRAPHICS DLL DIAGNOSTICS ===");
    static const wchar_t *sysDlls[] = {
        L"wowbox64.dll",
        L"d3d11.dll",
        L"dxgi.dll",
        L"d3d12.dll",
        L"xtajit.dll",
        L"libwow64fex.dll",
        L"libarm64ecfex.dll",
        L"ucrtbase.dll",
        L"vcruntime140.dll"
    };

    for (size_t i = 0; i < sizeof(sysDlls) / sizeof(sysDlls[0]); i++) {
        wchar_t fullPath[MAX_PATH] = {0};
        _snwprintf(fullPath, MAX_PATH - 1, L"C:\\windows\\system32\\%ls", sysDlls[i]);
        DWORD attr = GetFileAttributesW(fullPath);
        BOOL exists = (attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY));
        WORD machine = exists ? GetPEFileMachine(fullPath) : 0;

        char logLine[128] = {0};
        snprintf(logLine, sizeof(logLine), "[SYS32_CHECK] file=%ls exists=%d machine=0x%04X", sysDlls[i], exists, machine);
        LogRaw(logLine);
    }

    LogRaw("=== ENVIRONMENT VARIABLE DIAGNOSTICS ===");
    LPWCH envBlock = GetEnvironmentStringsW();
    if (envBlock) {
        LPWCH var = envBlock;
        while (*var) {
            if (wcsncmp(var, L"HODLL", 5) == 0 ||
                wcsncmp(var, L"BOX64_", 6) == 0 ||
                wcsncmp(var, L"FEX_", 4) == 0 ||
                wcsncmp(var, L"WINEDEBUG", 9) == 0) {
                char logLine[256] = {0};
                snprintf(logLine, sizeof(logLine), "[ENV_CHECK] %ls", var);
                LogRaw(logLine);
            }
            var += wcslen(var) + 1;
        }
        FreeEnvironmentStringsW(envBlock);
    }
}

/* ========================================================================= */
/* x64 Probe Invocation                                                     */
/* ========================================================================= */

static void RunX64ProbeIfPresent(void) {
    if (GetFileAttributesW(PROBE_EXE_PATH) == INVALID_FILE_ATTRIBUTES) {
        LogRaw("[PROBE_SKIPPED] G:\\_forever_\\x64_probe.exe not found");
        return;
    }

    LogRaw("[PROBE_START] Launching x64 probe test...");
    STARTUPINFOW si;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);

    PROCESS_INFORMATION pi;
    ZeroMemory(&pi, sizeof(pi));

    wchar_t probeCmd[512] = {0};
    _snwprintf(probeCmd, sizeof(probeCmd) / sizeof(wchar_t) - 1, L"\"%ls\"", PROBE_EXE_PATH);

    if (CreateProcessW(PROBE_EXE_PATH, probeCmd, NULL, NULL, FALSE, 0, NULL, GAME_WORKING_DIR, &si, &pi)) {
        DWORD waitResult = WaitForSingleObject(pi.hProcess, 15000);
        DWORD exitCode = 0;
        GetExitCodeProcess(pi.hProcess, &exitCode);
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);

        char logLine[128] = {0};
        snprintf(logLine, sizeof(logLine), "[PROBE_EXIT] wait=%lu exit_code=%lu (0x%08lX)", waitResult, (unsigned long)exitCode, (unsigned long)exitCode);
        LogRaw(logLine);
    } else {
        DWORD err = GetLastError();
        char logLine[128] = {0};
        snprintf(logLine, sizeof(logLine), "[PROBE_CREATE_FAILED] win32_err=%lu", err);
        LogRaw(logLine);
    }
}

static void LogDirStats(void) {
    WIN32_FIND_DATAW fd;
    HANDLE hFind = FindFirstFileW(L"G:\\_forever_\\Logs\\*", &fd);
    if (hFind == INVALID_HANDLE_VALUE) return;

    FILE *fp = _wfopen(LOG_FILE_PATH, L"a");
    if (!fp) {
        FindClose(hFind);
        return;
    }

    time_t now = time(NULL);
    struct tm *t = localtime(&now);
    char timeBuf[64] = {0};
    if (t) strftime(timeBuf, sizeof(timeBuf), "%Y-%m-%d %H:%M:%S", t);

    do {
        if (wcscmp(fd.cFileName, L".") == 0 || wcscmp(fd.cFileName, L"..") == 0) continue;

        ULONGLONG fileSize = ((ULONGLONG)fd.nFileSizeHigh << 32) | fd.nFileSizeLow;
        SYSTEMTIME stUtc, stLocal;
        FileTimeToSystemTime(&fd.ftLastWriteTime, &stUtc);
        SystemTimeToTzSpecificLocalTime(NULL, &stUtc, &stLocal);

        fprintf(fp, "[%s] [LOG_DIR_STAT] file=%ls size=%llu mtime=%04u-%02u-%02u %02u:%02u:%02u\n",
                timeBuf, fd.cFileName, (unsigned long long)fileSize,
                stLocal.wYear, stLocal.wMonth, stLocal.wDay,
                stLocal.wHour, stLocal.wMinute, stLocal.wSecond);
    } while (FindNextFileW(hFind, &fd));

    fflush(fp);
    fclose(fp);
    FindClose(hFind);
}

static void InitWrapperLog(void) {
    CreateDirectoryW(LOG_DIR_PATH, NULL);
    FILE *fp = _wfopen(LOG_FILE_PATH, L"w");
    if (!fp) return;

    char commitBuf[64] = {0};
    if (GetEnvironmentVariableA("WOW_BUILD_COMMIT", commitBuf, sizeof(commitBuf)) == 0 || commitBuf[0] == '\0') {
        if (GetEnvironmentVariableA("GIT_COMMIT", commitBuf, sizeof(commitBuf)) == 0 || commitBuf[0] == '\0') {
            FILE *fpCommit = _wfopen(L"E:\\build_commit.tmp", L"r");
            if (!fpCommit) fpCommit = _wfopen(L"G:\\_forever_\\build_commit.tmp", L"r");
            if (fpCommit) {
                if (fgets(commitBuf, sizeof(commitBuf), fpCommit)) {
                    TrimString(commitBuf);
                }
                fclose(fpCommit);
            }
        }
    }
#ifdef BUILD_COMMIT
    if (commitBuf[0] == '\0') {
        strncpy(commitBuf, BUILD_COMMIT, sizeof(commitBuf) - 1);
    }
#endif
    if (commitBuf[0] == '\0') {
        strncpy(commitBuf, "unknown", sizeof(commitBuf) - 1);
    }

    fprintf(fp, "[BUILD] commit=%s\n", commitBuf);
    fflush(fp);
    fclose(fp);
}

/* ========================================================================= */
/* Main Entry Point                                                         */
/* ========================================================================= */

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
    // Step 0a: Initialize wrapper log with build commit header as line 1
    InitWrapperLog();

    // Step 0b: Preserve container WINEDEBUG or default to diagnostic err+all,+loaddll,+module
    wchar_t curWineDebug[256] = {0};
    if (!GetEnvironmentVariableW(L"WINEDEBUG", curWineDebug, sizeof(curWineDebug) / sizeof(wchar_t)) || curWineDebug[0] == L'\0') {
        SetEnvironmentVariableW(L"WINEDEBUG", L"err+all,+loaddll,+module");
    }

    LogStep("WRAPPER_START", 0);

    // Step 1: Open token file
    FILE *fpToken = _wfopen(TOKEN_FILE_PATH, L"r");
    if (!fpToken) {
        DWORD err = GetLastError();
        LogStep("OPEN_TOKEN_FILE_FAILED", err);
        return 10;
    }

    char token[256] = {0};
    if (!fgets(token, sizeof(token), fpToken)) {
        fclose(fpToken);
        ShredAndDeleteTokenFile();
        LogStep("READ_TOKEN_EMPTY", GetLastError());
        return 11;
    }
    fclose(fpToken);

    // Step 2: Immediately shred and delete token file
    ShredAndDeleteTokenFile();
    LogStep("TOKEN_FILE_SHREDDED", 0);

    TrimString(token);
    if (strlen(token) < 10 || strncmp(token, "CN-", 3) != 0) {
        LogStep("TOKEN_FORMAT_INVALID", 0);
        return 12;
    }

    // Step 3: Parse account ID
    char *secondHyphen = strrchr(token, '-');
    if (!secondHyphen || secondHyphen == token) {
        LogStep("PARSE_ACCOUNT_ID_FAILED", 0);
        return 13;
    }
    const char *accountId = secondHyphen + 1;
    if (strlen(accountId) == 0) {
        LogStep("ACCOUNT_ID_EMPTY", 0);
        return 14;
    }

    // Step 4: Construct ACCOUNT_STATE JSON
    time_t now = time(NULL);
    char accountStateJson[1024] = {0};
    snprintf(accountStateJson, sizeof(accountStateJson),
             "{\"account_country\":\"CHN\",\"account_id\":%s,"
             "\"game_account\":{\"has_game_time\":false,\"id\":0,\"is_trial\":false,\"name\":\"\",\"region\":\"\"},"
             "\"igr_detected\":false,\"ratings_board_min_age\":0,\"timestamp\":%ld}",
             accountId, (long)now);

    char timestampStr[64] = {0};
    snprintf(timestampStr, sizeof(timestampStr), "%ld", (long)now);

    // Step 5: DPAPI Encrypt
    DATA_BLOB tokenBlob = {0};
    DATA_BLOB accountBlob = {0};
    DATA_BLOB stateBlob = {0};

    if (!ProtectDataBuffer(token, &tokenBlob)) {
        LogStep("ENCRYPT_TOKEN_FAILED", GetLastError());
        return 20;
    }

    if (!ProtectDataBuffer(PLACEHOLDER_ACCOUNT, &accountBlob)) {
        LocalFree(tokenBlob.pbData);
        LogStep("ENCRYPT_ACCOUNT_FAILED", GetLastError());
        return 21;
    }

    if (!ProtectDataBuffer(accountStateJson, &stateBlob)) {
        LocalFree(tokenBlob.pbData);
        LocalFree(accountBlob.pbData);
        LogStep("ENCRYPT_STATE_FAILED", GetLastError());
        return 22;
    }

    LogStep("DPAPI_ENCRYPT_SUCCESS", 0);

    // Step 6: Determine target architecture (prefer x64 if Wow.exe or flag exists)
    BOOL hasX64Exe = (GetFileAttributesW(GAME_X64_EXE_PATH) != INVALID_FILE_ATTRIBUTES);
    BOOL hasX64Flag = (GetFileAttributesW(X64_FLAG_PATH) != INVALID_FILE_ATTRIBUTES);
    BOOL useX64 = (hasX64Exe || hasX64Flag);

    // Step 6a: Write Launch Options\WoW Registry Keys
    HKEY hKey = NULL;
    DWORD disposition = 0;
    LSTATUS regStatus = RegCreateKeyExW(
        HKEY_CURRENT_USER,
        REG_KEY_PATH,
        0,
        NULL,
        REG_OPTION_NON_VOLATILE,
        KEY_WRITE,
        NULL,
        &hKey,
        &disposition
    );

    if (regStatus != ERROR_SUCCESS) {
        LocalFree(tokenBlob.pbData);
        LocalFree(accountBlob.pbData);
        LocalFree(stateBlob.pbData);
        LogStep("REG_CREATE_KEY_FAILED", (DWORD)regStatus);
        return 30;
    }

    RegSetValueExW(hKey, L"WEB_TOKEN", 0, REG_BINARY, tokenBlob.pbData, tokenBlob.cbData);
    RegSetValueExW(hKey, L"ACCOUNT", 0, REG_BINARY, accountBlob.pbData, accountBlob.cbData);
    RegSetValueExW(hKey, L"ACCOUNT_STATE", 0, REG_BINARY, stateBlob.pbData, stateBlob.cbData);

    RegSetValueExA(hKey, "ACCOUNT_TS", 0, REG_SZ, (const BYTE *)timestampStr, (DWORD)(strlen(timestampStr) + 1));
    RegSetValueExA(hKey, "REGION", 0, REG_SZ, (const BYTE *)"CN", 3);
    RegSetValueExA(hKey, "LOCALE", 0, REG_SZ, (const BYTE *)"zhCN", 5);
    RegSetValueExA(hKey, "LOCALE_AUDIO", 0, REG_SZ, (const BYTE *)"zhCN", 5);
    RegSetValueExA(hKey, "CONNECTION_STRING", 0, REG_SZ, (const BYTE *)"", 1);
    RegSetValueExA(hKey, "GAME_ACCOUNT", 0, REG_SZ, (const BYTE *)"", 1);
    if (useX64) {
        RegSetValueExA(hKey, "LAUNCH_64BIT", 0, REG_SZ, (const BYTE *)"true", 5);
    } else {
        RegSetValueExA(hKey, "LAUNCH_64BIT", 0, REG_SZ, (const BYTE *)"false", 6);
    }

    RegFlushKey(hKey);
    RegCloseKey(hKey);

    LocalFree(tokenBlob.pbData);
    LocalFree(accountBlob.pbData);
    LocalFree(stateBlob.pbData);
    LogStep("REG_WRITE_SUCCESS", 0);

    // Step 6b: Write Launch Options\BNA Registry Keys
    HKEY hBnaKey = NULL;
    DWORD bnaDisposition = 0;
    LSTATUS bnaStatus = RegCreateKeyExW(
        HKEY_CURRENT_USER,
        REG_BNA_KEY_PATH,
        0,
        NULL,
        REG_OPTION_NON_VOLATILE,
        KEY_WRITE,
        NULL,
        &hBnaKey,
        &bnaDisposition
    );

    if (bnaStatus != ERROR_SUCCESS) {
        LogStep("REG_CREATE_BNA_KEY_FAILED", (DWORD)bnaStatus);
        return 31;
    }

    static const struct {
        const char *name;
        const char *value;
    } bnaValues[] = {
        {"CONNECTION_STRING_CN",  "cn.actual.battlenet.com.cn"},
        {"CONNECTION_STRING_CXX", "cn-ptr.actual.battle.net"},
        {"CONNECTION_STRING_EU",  "eu.actual.battle.net"},
        {"CONNECTION_STRING_KR",  "kr.actual.battle.net"},
        {"CONNECTION_STRING_US",  "us.actual.battle.net"},
        {"CONNECTION_STRING_XX",  "test.actual.battle.net"}
    };

    for (size_t i = 0; i < sizeof(bnaValues) / sizeof(bnaValues[0]); ++i) {
        RegSetValueExA(hBnaKey, bnaValues[i].name, 0, REG_SZ,
                       (const BYTE *)bnaValues[i].value,
                       (DWORD)(strlen(bnaValues[i].value) + 1));
    }

    RegFlushKey(hBnaKey);
    RegCloseKey(hBnaKey);
    LogStep("REG_BNA_WRITE_SUCCESS", 0);

    // Step 6c: Diagnostics & Probing
    RunSystem32AndEnvDiagnostics();

    if (useX64) {
        // Step 6d: Inject FEXCore emulator and App-Local x64 DXVK override for x64 processes
        SetEnvironmentVariableW(L"HODLL", L"libwow64fex.dll");
        SetEnvironmentVariableW(L"HODLL64", L"libarm64ecfex.dll");
        SetEnvironmentVariableW(L"FEX_TSOENABLED", L"1");
        SetEnvironmentVariableW(L"FEX_HALFBARRIERTSOENABLED", L"1");
        SetEnvironmentVariableW(L"FEX_MULTIBLOCK", L"1");
        SetEnvironmentVariableW(L"FEX_VECTORTSOENABLED", L"0");
        SetEnvironmentVariableW(L"FEX_MEMCPYSETTSOENABLED", L"1");
        SetEnvironmentVariableW(L"FEX_SMCCHECKS", L"full");
        SetEnvironmentVariableW(L"FEX_HIDEHYPERVISORBIT", L"1");
        SetEnvironmentVariableW(L"FEX_HIDEHYBRID", L"1");
        SetEnvironmentVariableW(L"FEX_SMALLTSCSCALE", L"1");
        SetEnvironmentVariableW(L"FEX_VOLATILEMETADATA", L"1");
        SetEnvironmentVariableW(L"WINEDLLOVERRIDES", L"d3d11,dxgi=n,b");
        LogRaw("[ENV_SET] Configured FEXCore (SMCCHECKS=full, MULTIBLOCK=1, HIDEHYPERVISOR) and WINEDLLOVERRIDES=d3d11,dxgi=n,b for x64 execution");

        LogRaw("=== PE HEADER (Wow.exe) ===");
        LogPEHeaderDetails(GAME_X64_EXE_PATH, "Wow.exe");

        // Run x64 diagnostic probe
        RunX64ProbeIfPresent();
    }

    STARTUPINFOW si;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);

    PROCESS_INFORMATION pi;
    ZeroMemory(&pi, sizeof(pi));

    const wchar_t *targetExe = useX64 ? GAME_X64_EXE_PATH : GAME_ARM64_EXE_PATH;
    const wchar_t *targetCmd = useX64 ? GAME_X64_CMD_LINE : GAME_ARM64_CMD_LINE;

    wchar_t cmdLine[1024];
    wcsncpy(cmdLine, targetCmd, sizeof(cmdLine) / sizeof(wchar_t) - 1);
    cmdLine[sizeof(cmdLine) / sizeof(wchar_t) - 1] = L'\0';

    BOOL created = CreateProcessW(
        targetExe,
        cmdLine,
        NULL,
        NULL,
        FALSE,
        0,
        NULL,
        GAME_WORKING_DIR,
        &si,
        &pi
    );

    if (!created) {
        DWORD err = GetLastError();
        LogStep("CREATE_PROCESS_FAILED", err);
        return 40;
    }

    LogStep("GAME_PROCESS_STARTED", 0);

    // Step 8: Heartbeat loop and wait for target process (up to 10 minutes = 600 seconds)
    DWORD startTime = GetTickCount();
    DWORD exitCode = STILL_ACTIVE;

    while (TRUE) {
        DWORD waitResult = WaitForSingleObject(pi.hProcess, 5000);
        DWORD elapsedSec = (GetTickCount() - startTime) / 1000;

        if (waitResult == WAIT_OBJECT_0) {
            GetExitCodeProcess(pi.hProcess, &exitCode);
            break;
        }

        // Process is still running (waitResult == WAIT_TIMEOUT or other)
        GetExitCodeProcess(pi.hProcess, &exitCode);
        char heartbeatBuf[256] = {0};
        snprintf(heartbeatBuf, sizeof(heartbeatBuf),
                 "[HEARTBEAT] elapsed=%lus still_active=%d exit_code=%lu (0x%08lX)",
                 (unsigned long)elapsedSec, (exitCode == STILL_ACTIVE),
                 (unsigned long)exitCode, (unsigned long)exitCode);
        LogRaw(heartbeatBuf);

        // Record metadata of files in G:\_forever_\Logs\ (metadata only - strictly NO reading file contents)
        LogDirStats();

        if (elapsedSec >= 600) {
            LogRaw("[HEARTBEAT_TIMEOUT] 600s reached, waiting indefinitely for process exit...");
            WaitForSingleObject(pi.hProcess, INFINITE);
            GetExitCodeProcess(pi.hProcess, &exitCode);
            break;
        }
    }

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);

    LogExitCode("GAME_PROCESS_EXITED", exitCode);
    return (int)exitCode;
}

int main(int argc, char *argv[]) {
    return wWinMain(GetModuleHandle(NULL), NULL, GetCommandLineW(), SW_SHOWDEFAULT);
}
