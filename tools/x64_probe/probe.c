#include <windows.h>

/*
 * x64_probe.exe
 * Minimal x86_64 PE test binary with zero CRT / UCRT dependencies.
 * Only imports KERNEL32.dll.
 */

static const wchar_t LOG_DIR[]  = L"G:\\_forever_\\Logs";
static const wchar_t LOG_FILE[] = L"G:\\_forever_\\Logs\\x64_probe.log";

static void format_two_digits(char *buf, WORD num) {
    buf[0] = (char)('0' + (num / 10) % 10);
    buf[1] = (char)('0' + num % 10);
}

static void format_four_digits(char *buf, WORD num) {
    buf[0] = (char)('0' + (num / 1000) % 10);
    buf[1] = (char)('0' + (num / 100) % 10);
    buf[2] = (char)('0' + (num / 10) % 10);
    buf[3] = (char)('0' + num % 10);
}

static int format_dword_dec(char *buf, DWORD val) {
    char tmp[16];
    int len = 0;
    if (val == 0) {
        buf[0] = '0';
        return 1;
    }
    while (val > 0) {
        tmp[len++] = (char)('0' + (val % 10));
        val /= 10;
    }
    for (int i = 0; i < len; i++) {
        buf[i] = tmp[len - 1 - i];
    }
    return len;
}

void __cdecl mainCRTStartup(void) {
    CreateDirectoryW(LOG_DIR, NULL);

    HANDLE hFile = CreateFileW(
        LOG_FILE,
        FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL,
        OPEN_ALWAYS,
        FILE_ATTRIBUTE_NORMAL,
        NULL
    );

    if (hFile != INVALID_HANDLE_VALUE) {
        SYSTEMTIME st;
        GetLocalTime(&st);

        // Format: "[YYYY-MM-DD HH:MM:SS] x64_probe: executed successfully under FEX/Wine x64! pid="
        char msg[256];
        int idx = 0;
        msg[idx++] = '[';
        format_four_digits(&msg[idx], st.wYear);
        idx += 4;
        msg[idx++] = '-';
        format_two_digits(&msg[idx], st.wMonth);
        idx += 2;
        msg[idx++] = '-';
        format_two_digits(&msg[idx], st.wDay);
        idx += 2;
        msg[idx++] = ' ';
        format_two_digits(&msg[idx], st.wHour);
        idx += 2;
        msg[idx++] = ':';
        format_two_digits(&msg[idx], st.wMinute);
        idx += 2;
        msg[idx++] = ':';
        format_two_digits(&msg[idx], st.wSecond);
        idx += 2;
        msg[idx++] = ']';
        msg[idx++] = ' ';

        const char prefix[] = "x64_probe: executed successfully under FEX/Wine x64! pid=";
        for (int i = 0; prefix[i] != '\0'; i++) {
            msg[idx++] = prefix[i];
        }

        DWORD pid = GetCurrentProcessId();
        idx += format_dword_dec(&msg[idx], pid);

        msg[idx++] = '\r';
        msg[idx++] = '\n';

        DWORD written = 0;
        WriteFile(hFile, msg, (DWORD)idx, &written, NULL);
        CloseHandle(hFile);
    }

    ExitProcess(0);
}

/* Empty stub for GCC/Clang MinGW internal initialization */
void __main(void) {
}
