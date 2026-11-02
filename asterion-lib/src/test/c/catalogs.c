/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Lists the catalogs of a directory, and installs one, like a program which sets Asterion up:
 *
 *     catalogs DIRECTORY [NAME [CANCEL_AFTER]]
 *
 * NAME is installed in DIRECTORY, each event of the installation printing a line, and the installation
 * is cancelled after CANCEL_AFTER progress events, if given. The catalogs are listed before and after.
 */
// first: the header includes what it needs
#include "asterion.h"

#include <stdio.h>
#include <stdlib.h>

static void list(graal_isolatethread_t *thread, const char *directory) {
    // a first call counts the catalogs
    int count = asterion_catalogs(thread, directory, NULL, 0);
    asterion_catalog *catalogs = calloc(count > 0 ? count : 1, sizeof(asterion_catalog));
    asterion_catalogs(thread, directory, catalogs, count);
    for (int i = 0; i < count; i++) {
        asterion_catalog *c = &catalogs[i];
        printf("catalog: name=%s installed=%d available=%d size=%lld stars=%lld quads=%lld magnitude_limit=%.2f"
               " min_quad_arcmin=%.2f max_quad_deg=%.2f error=\"%s\" description=\"%s\"\n",
               c->name, c->installed, c->available, (long long) c->size, (long long) c->stars, (long long) c->quads,
               c->magnitude_limit, c->min_quad_arcmin, c->max_quad_deg, c->error, c->description);
    }
    free(catalogs);
}

typedef struct {
    int progress_events;
    int cancel_after;
} installation;

static int progress(const asterion_progress *p, void *user_data) {
    installation *state = user_data;
    switch (p->event) {
        case ASTERION_EVENT_STEP:
            printf("event: step step=\"%s\" total=%lld unit=%s\n", p->step, (long long) p->total, p->unit);
            break;
        case ASTERION_EVENT_PROGRESS:
            printf("event: progress completed=%lld total=%lld fraction=%.3f bytes=%lld\n", (long long) p->completed,
                   (long long) p->total, p->fraction, (long long) p->bytes);
            state->progress_events++;
            break;
        case ASTERION_EVENT_DETAIL:
            printf("event: detail message=\"%s\"\n", p->message);
            break;
        case ASTERION_EVENT_DONE:
            printf("event: done step=\"%s\"\n", p->step);
            break;
        case ASTERION_EVENT_INFO:
            printf("event: info message=\"%s\"\n", p->message);
            break;
    }
    fflush(stdout);
    return state->cancel_after > 0 && state->progress_events >= state->cancel_after;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s DIRECTORY [NAME [CANCEL_AFTER]]\n", argv[0]);
        return 2;
    }
    const char *directory = argv[1];
    graal_isolate_t *isolate;
    graal_isolatethread_t *thread;
    if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
        fprintf(stderr, "can't create the isolate\n");
        return 2;
    }
    if (!asterion_check_header(thread)) {
        fprintf(stderr, "this header doesn't match the library\n");
        return 3;
    }
    char text[1024];
    asterion_version(thread, text, sizeof text);
    printf("version=%s\n", text);
    // strings which don't fit are truncated, and their length tells the size they need
    char small[4];
    int length = asterion_version(thread, small, sizeof small);
    printf("truncated: version=%s length=%d sizeof_null=%d\n", small, length, asterion_sizeof(thread, NULL));
    asterion_default_catalog_directory(thread, text, sizeof text);
    printf("default_directory=%s\n", text);

    list(thread, directory);
    if (argc > 2) {
        installation state = {0, argc > 3 ? atoi(argv[3]) : 0};
        int status = asterion_install_catalog(thread, directory, argv[2], progress, &state);
        asterion_error(thread, text, sizeof text);
        printf("installed: status=%d error=\"%s\"\n", status, status == 0 ? "" : text);
        list(thread, directory);
    }
    graal_tear_down_isolate(thread);
    return 0;
}
