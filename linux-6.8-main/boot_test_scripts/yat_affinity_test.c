#define _GNU_SOURCE
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>

/* Four-vCPU guest required. Inspect AJLR intent, not only core fallback. */
static int phase(int first)
{
    cpu_set_t mask;
    CPU_ZERO(&mask); CPU_SET(first, &mask); CPU_SET(first + 1, &mask);
    if (sched_setaffinity(0, sizeof(mask), &mask)) { perror("affinity"); return 1; }
    cpu_set_t effective;
    CPU_ZERO(&effective);
    if (sched_getaffinity(0, sizeof(effective), &effective)) { perror("getaffinity"); return 6; }
    if (!CPU_EQUAL(&effective, &mask)) {
        printf("MASK-MISMATCH requested=%d,%d count=%d\n", first, first+1, CPU_COUNT(&effective));
        return 7;
    }
    for (int i = 0; i < 100; i++) {
        struct timespec pause = {0, 2000000};
        if (nanosleep(&pause, NULL)) { perror("nanosleep"); return 1; }
        int actual = sched_getcpu();
        if (actual != first && actual != first + 1) { printf("RUN-MISMATCH actual=%d mask=%d,%d\n", actual, first, first+1); return 2; }
    }
    FILE *f = fopen("/sys/kernel/debug/yat_casched/placements", "r");
    if (!f) { perror("placements"); return 3; }
    char line[256]; int found = 0, bad = 0;
    while (fgets(line, sizeof(line), f)) {
        int job, pid, prio, assigned, actual;
        if (sscanf(line, "%d%d%d%d%d", &job, &pid, &prio, &assigned, &actual) == 5 && pid == getpid()) {
            found = 1;
            printf("PHASE mask=%d,%d assigned=%d actual=%d\n", first, first+1, assigned, actual);
            bad = assigned != first && assigned != first + 1;
        }
    }
    fclose(f);
    return !found ? 4 : bad ? 5 : 0;
}
int main(void)
{
    struct sched_param param = {0};
    if (sched_setscheduler(0, 8, &param)) { perror("SCHED_YAT_CASCHED"); return 1; }
    int rc = phase(0);
    if (!rc) rc = phase(2);
    if (!rc) rc = phase(0);
    printf("YAT-AFFINITY %s rc=%d\n", rc ? "FAIL" : "PASS", rc);
    return rc;
}