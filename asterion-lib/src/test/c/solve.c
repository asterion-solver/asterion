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
 * Solves pixels which are in memory with the library, like an acquisition program would:
 *
 *     solve CATALOGS PIXELS TYPE WIDTH HEIGHT [REPEAT [BAYER]]
 *
 * PIXELS is a file of raw samples, row after row, without padding, of TYPE (1: uint8, 2: uint16,
 * 3: float32), and BAYER is 1 for a raw frame of a color sensor. The image is solved REPEAT times,
 * each solve printing a line of key=value pairs, then once more with the details of the result.
 */
#include <locale.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "asterion.h"

static double now_ms(void) {
    // timespec_get is C11, which Visual C++ has too, unlike clock_gettime
    struct timespec t;
    timespec_get(&t, TIME_UTC);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static int fail(graal_isolatethread_t *thread, const char *what) {
    char message[512];
    asterion_error(thread, message, sizeof message);
    fprintf(stderr, "%s: %s\n", what, message);
    return 2;
}

/* The number of catalog files which the process maps, or -1 where the system doesn't tell (no /proc). */
static int mapped_catalogs(void) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == NULL) {
        return -1;
    }
    char line[4096];
    int count = 0;
    while (fgets(line, sizeof line, maps)) {
        if (strstr(line, ".astx") != NULL) {
            count++;
        }
    }
    fclose(maps);
    return count;
}

static void print(int status, const asterion_solution *s, double call_ms) {
    printf("solved=%d ra=%.6f dec=%.6f scale=%.5f rotation=%.4f flipped=%d matched=%d stars=%d index=%s"
           " read_ms=%.2f detect_ms=%.2f solve_ms=%.2f call_ms=%.2f\n",
           status == ASTERION_SOLVED, s->ra, s->dec, s->scale, s->rotation, s->flipped, s->matched_stars, s->stars, s->index,
           s->read_ms, s->detect_ms, s->solve_ms, call_ms);
}

int main(int argc, char **argv) {
    if (argc < 6) {
        fprintf(stderr, "usage: %s CATALOGS PIXELS TYPE WIDTH HEIGHT [REPEAT [BAYER]]\n", argv[0]);
        return 2;
    }
    char *catalogs = argv[1];
    const char *file = argv[2];
    int type = atoi(argv[3]);
    int width = atoi(argv[4]);
    int height = atoi(argv[5]);
    int repeat = argc > 6 ? atoi(argv[6]) : 1;
    int bayer = argc > 7 ? atoi(argv[7]) : 0;
    int bytes = type == ASTERION_UINT8 ? 1 : type == ASTERION_UINT16 ? 2 : 4;
    long long stride = (long long) width * bytes;
    size_t size = (size_t) width * height * bytes;

    void *pixels = malloc(size);
    FILE *in = fopen(file, "rb");
    if (pixels == NULL || in == NULL || fread(pixels, 1, size, in) != size) {
        fprintf(stderr, "can't read %zu bytes from %s\n", size, file);
        return 2;
    }
    fclose(in);

    // the library must leave the locale of the program alone: it decides how numbers are printed and parsed
    char locale[256];
    snprintf(locale, sizeof locale, "%s", setlocale(LC_ALL, NULL));
    graal_isolate_t *isolate;
    graal_isolatethread_t *thread;
    if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
        fprintf(stderr, "can't create the isolate\n");
        return 2;
    }
    if (strcmp(locale, setlocale(LC_ALL, NULL)) != 0) {
        fprintf(stderr, "creating the isolate changed the locale from %s to %s\n", locale, setlocale(LC_ALL, NULL));
        return 3;
    }
    if (!asterion_check_header(thread)) {
        fprintf(stderr, "this header doesn't match the library\n");
        return 3;
    }
    // errors are reported, with their message
    void *solver;
    char message[512];
    if (asterion_open(thread, "/no/catalogs/here", &solver) != ASTERION_ERROR || asterion_error(thread, message, sizeof message) == 0) {
        fprintf(stderr, "opening a directory without catalogs should fail\n");
        return 3;
    }
    printf("error=%s\n", message);

    double start = now_ms();
    if (asterion_open(thread, catalogs, &solver) != 0) {
        return fail(thread, "can't open the catalogs");
    }
    printf("open_ms=%.2f\n", now_ms() - start);

    // the defaults: what a program running all night does for each frame
    asterion_options options;
    asterion_default_options(thread, &options);
    asterion_solution solution;
    for (int i = 0; i < repeat; i++) {
        start = now_ms();
        int status = asterion_solve(thread, solver, pixels, type, width, height, stride, bayer, &options, &solution, NULL);
        double call_ms = now_ms() - start;
        if (status == ASTERION_ERROR) {
            return fail(thread, "can't solve");
        }
        print(status, &solution, call_ms);
    }

    // the details of a result
    void *result;
    int status = asterion_solve(thread, solver, pixels, type, width, height, stride, bayer, NULL, &solution, &result);
    if (status != ASTERION_SOLVED) {
        return fail(thread, "can't solve again");
    }
    int objects = asterion_result_object_count(thread, result);
    asterion_object object = {0};
    if (objects > 0 && asterion_result_object(thread, result, 0, &object) != 0) {
        return fail(thread, "can't read an object");
    }
    int matches = asterion_result_match_count(thread, result);
    asterion_match match = {0};
    if (matches > 0 && asterion_result_match(thread, result, 0, &match) != 0) {
        return fail(thread, "can't read a match");
    }
    // the center of the image, there and back again
    double ra, dec, x, y;
    if (asterion_result_pixel_to_sky(thread, result, (width - 1) / 2.0, (height - 1) / 2.0, &ra, &dec) != 0
            || asterion_result_sky_to_pixel(thread, result, ra, dec, &x, &y) != 0) {
        return fail(thread, "can't convert positions");
    }
    int json_length = asterion_result_json(thread, result, NULL, 0);
    char *json = malloc(json_length + 1);
    asterion_result_json(thread, result, json, json_length + 1);
    printf("details: objects=%d object=\"%s\" type=\"%s\" matches=%d match_residual=%.3f center_ra=%.6f center_dec=%.6f"
           " back_x=%.4f back_y=%.4f sip_order=%d crpix1=%.3f json_length=%d json_start=%.16s\n",
           objects, object.name, object.type, matches, match.residual, ra, dec, x, y, solution.sip_order, solution.crpix[0],
           json_length, json);
    free(json);
    asterion_result_free(thread, result);

    // the options limit the search: the right region solves, another one doesn't
    asterion_options region;
    asterion_default_options(thread, &region);
    region.ra = solution.ra;
    region.dec = solution.dec;
    region.radius = 2;
    int right = asterion_solve(thread, solver, pixels, type, width, height, stride, bayer, &region, &solution, NULL);
    region.ra = fmod(region.ra + 90, 360);
    region.timeout = 5;
    int wrong = asterion_solve(thread, solver, pixels, type, width, height, stride, bayer, &region, &solution, NULL);
    printf("regions: right=%d wrong=%d\n", right, wrong);
    // options which weren't filled by asterion_default_options are rejected
    asterion_options zeros = {0};
    int rejected = asterion_solve(thread, solver, pixels, type, width, height, stride, bayer, &zeros, &solution, NULL);
    asterion_error(thread, message, sizeof message);
    printf("zero_options: status=%d error=\"%s\"\n", rejected, message);

    // closing the solver releases the memory of its catalogs at once
    int mapped = mapped_catalogs();
    if (asterion_close(thread, solver) != 0) {
        return fail(thread, "can't close the solver");
    }
    printf("catalogs: mapped_before_close=%d mapped_after_close=%d\n", mapped, mapped_catalogs());
    graal_tear_down_isolate(thread);
    free(pixels);
    return 0;
}
