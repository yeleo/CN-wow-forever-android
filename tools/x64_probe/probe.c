#include <windows.h>
#include <stdio.h>
#include <time.h>

static const wchar_t* const LOG_DIR_PATH  = L"G:\\_forever_\\Logs";
static const wchar_t* const LOG_FILE_PATH = L"G:\\_forever_\\Logs\\x64_probe.log";

static const wchar_t* const PROBE_DLLS[] = {
    L"vcruntime140.dll",
    L"vcruntime140_1.dll",
    L"msvcp140.dll",
    L"msvcp140_1.dll",
    L"concrt140.dll",
    L"ucrtbase.dll",
    L"d3d11.dll",
    L"dxgi.dll",
    L"d3d12.dll",
    L"dbghelp.dll"
};

int main(int argc, char *argv[]) {
    CreateDirectoryW(LOG_DIR_PATH, NULL);
    FILE *fp = _wfopen(LOG_FILE_PATH, L"a");
    if (!fp) return 1;

    time_t now = time(NULL);
    struct tm *t = localtime(&now);
    char timeBuf[64] = {0};
    if (t) {
        strftime(timeBuf, sizeof(timeBuf), "%Y-%m-%d %H:%M:%S", t);
    } else {
        snprintf(timeBuf, sizeof(timeBuf), "%ld", (long)now);
    }

    fprintf(fp, "\n=== [%s] x64 Probe Started (PID=%lu, Arch=x86_64) ===\n", timeBuf, GetCurrentProcessId());
    fflush(fp);

    size_t count = sizeof(PROBE_DLLS) / sizeof(PROBE_DLLS[0]);
    for (size_t i = 0; i < count; i++) {
        const wchar_t *dllName = PROBE_DLLS[i];
        char narrowName[128] = {0};
        snprintf(narrowName, sizeof(narrowName), "%ls", dllName);

        HMODULE hMod = LoadLibraryW(dllName);
        if (hMod) {
            fprintf(fp, "[%s] [LOAD_OK] %s (handle=%p)\n", timeBuf, narrowName, hMod);
            // Free library to avoid leaking resources
            FreeLibrary(hMod);
        } else {
            DWORD err = GetLastError();
            fprintf(fp, "[%s] [LOAD_FAILED] %s win32_err=%lu\n", timeBuf, narrowName, err);
        }
        fflush(fp);
    }

    fprintf(fp, "=== [%s] x64 Probe Completed (exit=0) ===\n", timeBuf);
    fflush(fp);
    fclose(fp);
    return 0;
}
