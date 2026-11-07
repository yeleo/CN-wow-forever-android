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
 * 1. Reads ST token from E:\bnet_token.tmp (Android app private storage mapping).
 * 2. Parses account_id from ST (CN-<32hex>-<accountId>).
 * 3. Encrypts ACCOUNT (placeholder), ACCOUNT_STATE (JSON), and WEB_TOKEN (ST)
 *    using Wine's CryptProtectData with the Blizzard 16-byte entropy.
 * 4. Writes Launch Options to HKCU\Software\Blizzard Entertainment\Battle.net\Launch Options\WoW.
 * 5. Securely shreds and deletes E:\bnet_token.tmp immediately.
 * 6. Launches G:\_forever_\Wow-ARM64.exe with:
 *    -d3d11 -launcherlogin -uid wowf
 *    with lpCurrentDirectory explicitly set to G:\_forever_.
 * 7. Waits for Wow-ARM64.exe to exit and transparently passes through its exit code.
 * 8. Records step milestones and Win32 error codes to G:\_forever_\Logs\launcher_wrapper.log.
 */

static const wchar_t* const TOKEN_FILE_PATH   = L"E:\\bnet_token.tmp";
static const wchar_t* const LOG_DIR_PATH      = L"G:\\_forever_\\Logs";
static const wchar_t* const LOG_FILE_PATH     = L"G:\\_forever_\\Logs\\launcher_wrapper.log";
static const wchar_t* const GAME_X64_EXE_PATH = L"G:\\_forever_\\Wow.exe";
static const wchar_t* const GAME_ARM64_EXE_PATH = L"G:\\_forever_\\Wow-ARM64.exe";
static const wchar_t* const X64_FLAG_PATH     = L"G:\\_forever_\\use_x64.flag";
static const wchar_t* const GAME_WORKING_DIR  = L"G:\\_forever_";
static const wchar_t* const GAME_X64_CMD_LINE = L"\"G:\\_forever_\\Wow.exe\" -d3d11 -launcherlogin -uid wowf";
static const wchar_t* const GAME_ARM64_CMD_LINE = L"\"G:\\_forever_\\Wow-ARM64.exe\" -d3d11 -launcherlogin -uid wowf";
static const wchar_t* const REG_KEY_PATH      = L"Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\WoW";
static const wchar_t* const REG_BNA_KEY_PATH  = L"Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\BNA";

static const char* const PLACEHOLDER_ACCOUNT  = "wowf_cn_user@battlenet.com.cn";

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

static void TrimString(char *s) {
    int len = (int)strlen(s);
    while (len > 0 && (s[len - 1] == '\r' || s[len - 1] == '\n' || s[len - 1] == ' ' || s[len - 1] == '\t')) {
        s[--len] = '\0';
    }
}

static BOOL ProtectDataBuffer(const char *plainText, DATA_BLOB *pOut) {
    DATA_BLOB in;
    DATA_BLOB entropy;
    in.pbData = (BYTE *)plainText;
    in.cbData = (DWORD)strlen(plainText);
    entropy.pbData = (BYTE *)ENTROPY;
    entropy.cbData = sizeof(ENTROPY);
    return CryptProtectData(&in, NULL, &entropy, NULL, NULL, 0, pOut);
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

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
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
        DWORD err = GetLastError();
        LogStep("DPAPI_WEB_TOKEN_FAILED", err);
        return 20;
    }

    if (!ProtectDataBuffer(PLACEHOLDER_ACCOUNT, &accountBlob)) {
        DWORD err = GetLastError();
        LocalFree(tokenBlob.pbData);
        LogStep("DPAPI_ACCOUNT_FAILED", err);
        return 21;
    }

    if (!ProtectDataBuffer(accountStateJson, &stateBlob)) {
        DWORD err = GetLastError();
        LocalFree(tokenBlob.pbData);
        LocalFree(accountBlob.pbData);
        LogStep("DPAPI_ACCOUNT_STATE_FAILED", err);
        return 22;
    }
    LogStep("DPAPI_ENCRYPT_SUCCESS", 0);

    // Step 5b: Detect Launch Target (x64 Wow.exe vs ARM64 Wow-ARM64.exe)
    BOOL useX64 = FALSE;
    if (GetFileAttributesW(GAME_X64_EXE_PATH) != INVALID_FILE_ATTRIBUTES ||
        GetFileAttributesW(X64_FLAG_PATH) != INVALID_FILE_ATTRIBUTES) {
        useX64 = TRUE;
        LogStep("LAUNCH_TARGET_X64", 0);
    } else {
        LogStep("LAUNCH_TARGET_ARM64", 0);
    }

    // Step 6: Write Registry
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

    // Write REG_BINARY DPAPI blobs
    RegSetValueExW(hKey, L"WEB_TOKEN", 0, REG_BINARY, tokenBlob.pbData, tokenBlob.cbData);
    RegSetValueExW(hKey, L"ACCOUNT", 0, REG_BINARY, accountBlob.pbData, accountBlob.cbData);
    RegSetValueExW(hKey, L"ACCOUNT_STATE", 0, REG_BINARY, stateBlob.pbData, stateBlob.cbData);

    // Write REG_SZ String values
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

    // Step 7: Launch target game executable
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

    // Step 8: Wait for target process to exit and forward exit code
    WaitForSingleObject(pi.hProcess, INFINITE);

    DWORD exitCode = 0;
    if (!GetExitCodeProcess(pi.hProcess, &exitCode)) {
        exitCode = GetLastError();
    }

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);

    LogStep("GAME_PROCESS_EXITED", exitCode);
    return (int)exitCode;
}

int main(int argc, char *argv[]) {
    return wWinMain(GetModuleHandle(NULL), NULL, GetCommandLineW(), SW_SHOWDEFAULT);
}

