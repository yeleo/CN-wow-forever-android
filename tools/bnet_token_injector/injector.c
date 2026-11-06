#include <windows.h>
#include <wincrypt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/*
 * bnet_token_injector.c
 * Standalone Win32 C injector for Battle.net WoW CN SSO Web Token.
 * Encrypts ACCOUNT, ACCOUNT_STATE, and WEB_TOKEN using Wine's native CryptProtectData
 * with the standard Battle.net 16-byte entropy, and writes Launch Options to HKCU.
 *
 * Input format (read from temporary file, shredded immediately after reading):
 *   Line 1: ST Token (e.g. CN-0123456789abcdef0123456789abcdef-123456789)
 *   Line 2: Account Name / Email (e.g. user@163.com)
 */

static const BYTE ENTROPY[16] = {
    0xC8, 0x76, 0xF4, 0xAE, 0x4C, 0x95, 0x2E, 0xFE,
    0xF2, 0xFA, 0x0F, 0x54, 0x19, 0xC0, 0x9C, 0x43
};

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

static BOOL ShredAndDeleteFile(const char *filePath) {
    FILE *fp = fopen(filePath, "rb+");
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
    return DeleteFileA(filePath);
}

int main(int argc, char *argv[]) {
    const char *inputPath = (argc > 1 && argv[1][0] != '\0') ? argv[1] : "E:\\bnet_token.tmp";
    FILE *fp = fopen(inputPath, "r");
    if (!fp) {
        // Fallback to C:\ if E:\ is not found
        inputPath = "C:\\bnet_token.tmp";
        fp = fopen(inputPath, "r");
    }

    if (!fp) {
        fprintf(stderr, "[injector] Error: cannot open token input file\n");
        return 1;
    }

    char token[256] = {0};
    char email[256] = {0};

    if (!fgets(token, sizeof(token), fp)) {
        fclose(fp);
        ShredAndDeleteFile(inputPath);
        fprintf(stderr, "[injector] Error: failed to read token line\n");
        return 2;
    }

    if (!fgets(email, sizeof(email), fp)) {
        // Email optional, fallback to empty if missing
        email[0] = '\0';
    }

    fclose(fp);
    // Shred and delete file immediately to prevent credentials staying on disk
    ShredAndDeleteFile(inputPath);

    TrimString(token);
    TrimString(email);

    if (strlen(token) < 10 || strncmp(token, "CN-", 3) != 0) {
        fprintf(stderr, "[injector] Error: invalid token format (must start with CN-)\n");
        return 3;
    }

    // Parse Account ID from token: CN-<32hex>-<accountId>
    char *secondHyphen = strrchr(token, '-');
    if (!secondHyphen || secondHyphen == token) {
        fprintf(stderr, "[injector] Error: token missing account ID separator\n");
        return 4;
    }
    const char *accountId = secondHyphen + 1;
    if (strlen(accountId) == 0) {
        fprintf(stderr, "[injector] Error: empty account ID in token\n");
        return 5;
    }

    // Build ACCOUNT_STATE JSON
    time_t now = time(NULL);
    char accountStateJson[1024] = {0};
    snprintf(accountStateJson, sizeof(accountStateJson),
             "{\"account_country\":\"CHN\",\"account_id\":%s,"
             "\"game_account\":{\"has_game_time\":false,\"id\":0,\"is_trial\":false,\"name\":\"\",\"region\":\"\"},"
             "\"igr_detected\":false,\"ratings_board_min_age\":0,\"timestamp\":%ld}",
             accountId, (long)now);

    char timestampStr[64] = {0};
    snprintf(timestampStr, sizeof(timestampStr), "%ld", (long)now);

    // DPAPI Encryption
    DATA_BLOB tokenBlob = {0};
    DATA_BLOB emailBlob = {0};
    DATA_BLOB stateBlob = {0};

    if (!ProtectDataBuffer(token, &tokenBlob)) {
        fprintf(stderr, "[injector] Error: CryptProtectData failed for WEB_TOKEN (code: %lu)\n", GetLastError());
        return 10;
    }

    if (!ProtectDataBuffer(email, &emailBlob)) {
        LocalFree(tokenBlob.pbData);
        fprintf(stderr, "[injector] Error: CryptProtectData failed for ACCOUNT (code: %lu)\n", GetLastError());
        return 11;
    }

    if (!ProtectDataBuffer(accountStateJson, &stateBlob)) {
        LocalFree(tokenBlob.pbData);
        LocalFree(emailBlob.pbData);
        fprintf(stderr, "[injector] Error: CryptProtectData failed for ACCOUNT_STATE (code: %lu)\n", GetLastError());
        return 12;
    }

    // Write to Registry
    const char *regSubKey = "Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\WoW";
    HKEY hKey = NULL;
    DWORD disposition = 0;
    LSTATUS status = RegCreateKeyExA(
        HKEY_CURRENT_USER,
        regSubKey,
        0,
        NULL,
        REG_OPTION_NON_VOLATILE,
        KEY_WRITE,
        NULL,
        &hKey,
        &disposition
    );

    if (status != ERROR_SUCCESS) {
        LocalFree(tokenBlob.pbData);
        LocalFree(emailBlob.pbData);
        LocalFree(stateBlob.pbData);
        fprintf(stderr, "[injector] Error: RegCreateKeyExA failed (code: %ld)\n", (long)status);
        return 20;
    }

    // Write REG_BINARY DPAPI values
    RegSetValueExA(hKey, "WEB_TOKEN", 0, REG_BINARY, tokenBlob.pbData, tokenBlob.cbData);
    RegSetValueExA(hKey, "ACCOUNT", 0, REG_BINARY, emailBlob.pbData, emailBlob.cbData);
    RegSetValueExA(hKey, "ACCOUNT_STATE", 0, REG_BINARY, stateBlob.pbData, stateBlob.cbData);

    // Write REG_SZ String values
    RegSetValueExA(hKey, "ACCOUNT_TS", 0, REG_SZ, (const BYTE *)timestampStr, (DWORD)(strlen(timestampStr) + 1));
    RegSetValueExA(hKey, "REGION", 0, REG_SZ, (const BYTE *)"CN", 3);
    RegSetValueExA(hKey, "LOCALE", 0, REG_SZ, (const BYTE *)"zhCN", 5);
    RegSetValueExA(hKey, "LOCALE_AUDIO", 0, REG_SZ, (const BYTE *)"zhCN", 5);
    RegSetValueExA(hKey, "CONNECTION_STRING", 0, REG_SZ, (const BYTE *)"", 1);
    RegSetValueExA(hKey, "GAME_ACCOUNT", 0, REG_SZ, (const BYTE *)"", 1);
    RegSetValueExA(hKey, "LAUNCH_64BIT", 0, REG_SZ, (const BYTE *)"false", 6);

    RegFlushKey(hKey);
    RegCloseKey(hKey);

    LocalFree(tokenBlob.pbData);
    LocalFree(emailBlob.pbData);
    LocalFree(stateBlob.pbData);

    printf("[injector] Success: Battle.net DPAPI launch credentials injected for accountId=%s\n", accountId);
    return 0;
}

