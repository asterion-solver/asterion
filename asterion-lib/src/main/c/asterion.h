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
 * Asterion Solver, as a library: installs catalogs, and solves images which are in memory, such as the
 * frames of a camera. README.md tells how the functions fit together:
 *
 *     graal_isolate_t *isolate;
 *     graal_isolatethread_t *thread;
 *     graal_create_isolate(NULL, &isolate, &thread);         // once: the library runs in a GraalVM isolate
 *
 *     asterion_install_catalog(thread, NULL, "tycho2", progress, NULL);   // once per machine
 *
 *     void *solver;
 *     asterion_open(thread, NULL, &solver);                   // the catalogs stay open
 *     asterion_solution solution;
 *     if (asterion_solve(thread, solver, pixels, ASTERION_UINT16, width, height, width * 2, 0, NULL, &solution, NULL)
 *             == ASTERION_SOLVED) {
 *         printf("%f %f\n", solution.ra, solution.dec);
 *     }
 *
 *     asterion_close(thread, solver);                         // releases the catalogs at once
 *     graal_tear_down_isolate(thread);
 *
 * Functions which fail return ASTERION_ERROR, and asterion_error gives the message. Strings are UTF-8
 * and null terminated; the functions which copy one into a buffer return its length in bytes, which
 * may be larger than the buffer: the string is then truncated, without splitting a character. A
 * directory of catalogs which is NULL is the default one, see asterion_default_catalog_directory.
 */
#ifndef ASTERION_H
#define ASTERION_H

#include <stddef.h>
#include <stdint.h>
#include <graal_isolate.h>

/* The types of the samples of the pixels, one per pixel, in the native byte order. */
#define ASTERION_UINT8 1
#define ASTERION_UINT16 2
#define ASTERION_FLOAT32 3

/* What asterion_solve returns. */
#define ASTERION_SOLVED 1
#define ASTERION_NOT_SOLVED 0
#define ASTERION_ERROR (-1)
/* What asterion_install_catalog and asterion_solve_cancellable return when the program cancels. */
#define ASTERION_CANCELLED (-2)

/* The handedness of images. */
#define ASTERION_PARITY_BOTH 0
#define ASTERION_PARITY_NORMAL 1
#define ASTERION_PARITY_FLIPPED 2

/* The largest order of the SIP polynomials, and of their inverses, which are fitted 2 orders higher. */
#define ASTERION_MAX_SIP_ORDER 5
#define ASTERION_MAX_SIP_INVERSE_ORDER 7

/*
 * The options of a search, the ones of the command line tool: asterion_default_options fills them
 * with their defaults. The fields which are NAN are unknown.
 */
typedef struct {
    double ra;              /* the right ascension of the center of the region to search, in degrees, or NAN for the whole sky */
    double dec;             /* the declination of the center of the region to search, in degrees, or NAN */
    double radius;          /* the radius of the region to search, in degrees: nothing is searched beyond */
    double scale_low;       /* the smallest scale of the image, in arcseconds per pixel, or NAN */
    double scale_high;      /* the largest scale of the image, in arcseconds per pixel, or NAN */
    double fov;             /* the approximate width of the field of view, in degrees, 30% tolerated, or NAN */
    double pixel_size;      /* the size of the pixels, in micrometers, binning included, for the focal length, or NAN */
    double timeout;         /* how long to search before giving up, in seconds */
    double sigma;           /* the threshold of the detection of stars, in units of background noise */
    double epoch;           /* the date of the image, as a Julian year, for proper motions, or NAN for now */
    int32_t parity;         /* ASTERION_PARITY_BOTH, ASTERION_PARITY_NORMAL or ASTERION_PARITY_FLIPPED */
    int32_t sip_order;      /* the order of the SIP polynomials: 0 to disable, 2 to 5, or -1 to choose */
    int32_t binning;        /* the binning applied before detecting stars, or 0 to choose */
    int32_t max_stars;      /* the largest number of stars to detect */
    int32_t quad_stars;     /* the number of stars, the brightest first, used to search for a match */
    int32_t padding;
} asterion_options;

/* The solution of an image, which asterion_solve fills in. Without a solution, its fields are NAN or 0. */
typedef struct {
    double ra;              /* the right ascension of the center of the image, J2000, in degrees */
    double dec;             /* the declination of the center of the image, J2000, in degrees */
    double scale;           /* the scale, in arcseconds per pixel */
    double rotation;        /* the position angle of the Y axis of the image, in degrees east of north */
    double field_width;     /* the width of the field of view, in degrees */
    double field_height;    /* the height of the field of view, in degrees */
    double rms;             /* the residuals of the matched stars, in arcseconds */
    double log_odds;        /* the confidence in the solution */
    double focal_length;    /* the focal length, in millimeters, when the size of the pixels is known, else NAN */
    double pixel_size;      /* the size of the pixels used for the focal length, in micrometers, or NAN */
    double fwhm_pixels;     /* the median width of the stars, in pixels: once solved, of gaussians fitted to the
                               matched stars which are neither faint nor saturated, if there are enough */
    double fwhm_arcsec;     /* fwhm_pixels, in arcseconds */
    double crpix[2];        /* the WCS, with the conventions of FITS: CRPIX1, CRPIX2 */
    double crval[2];        /* CRVAL1, CRVAL2, in degrees */
    double cd[4];           /* CD1_1, CD1_2, CD2_1, CD2_2, in degrees per pixel */
    double sip_a[ASTERION_MAX_SIP_ORDER + 1][ASTERION_MAX_SIP_ORDER + 1];   /* A_p_q is sip_a[p][q] */
    double sip_b[ASTERION_MAX_SIP_ORDER + 1][ASTERION_MAX_SIP_ORDER + 1];   /* B_p_q */
    double sip_ap[ASTERION_MAX_SIP_INVERSE_ORDER + 1][ASTERION_MAX_SIP_INVERSE_ORDER + 1];  /* AP_p_q, the inverse polynomials */
    double sip_bp[ASTERION_MAX_SIP_INVERSE_ORDER + 1][ASTERION_MAX_SIP_INVERSE_ORDER + 1];  /* BP_p_q */
    double read_ms;         /* how long copying the pixels took, in milliseconds */
    double detect_ms;       /* how long detecting the stars took */
    double solve_ms;        /* how long solving took */
    int32_t sip_order;      /* A_ORDER and B_ORDER, or 0 without distortion */
    int32_t sip_inverse_order; /* AP_ORDER and BP_ORDER, or 0 */
    int32_t flipped;        /* 1 if the image is mirrored */
    int32_t matched_stars;  /* the number of stars matched with the catalog */
    int32_t stars;          /* the number of stars detected in the image */
    char index[64];         /* the name of the catalog which solved the image, such as "gaia-500" */
    int32_t padding;
} asterion_solution;

/* A deep sky object of the image: a galaxy, a nebula, a cluster... the most prominent first. */
typedef struct {
    double ra;              /* the right ascension of its center, J2000, in degrees */
    double dec;             /* the declination of its center, in degrees */
    double x;               /* its position in the image, in pixels, which may be outside of it */
    double y;
    double distance;        /* the distance of its center to the one of the image, in degrees */
    double major_arcmin;    /* its major axis, in arcminutes, or NAN */
    double minor_arcmin;    /* its minor axis, in arcminutes, or NAN */
    double angle;           /* the position angle of its major axis, in degrees east of north, or NAN */
    double magnitude;       /* its visual magnitude, or NAN */
    int32_t messier;        /* its number in the Messier catalog, or 0 */
    char name[32];          /* the name it's best known by: "M 31", "NGC 7000" */
    char designation[32];   /* its designation in its catalog: "NGC 224" */
    char common_name[128];  /* its common names, separated by commas, or "" */
    char aliases[256];      /* its designations in other catalogs, separated by commas, or "" */
    char type[32];          /* its kind, in words: "galaxy", "HII region"... */
} asterion_object;

/* A star of the image which was matched with the catalog. */
typedef struct {
    double x;               /* its position in the image, in pixels */
    double y;
    double ra;              /* the position of the catalog star at the date of the image, in degrees */
    double dec;
    double residual;        /* the distance between the two according to the solution, in arcseconds */
} asterion_match;

/* A catalog, which asterion_catalogs describes. */
typedef struct {
    char name[64];          /* its name, such as "gaia-500", or the one of an index file: its file name without .astx */
    char description[256];  /* what it is, and the fields of view it's for, or "" if Asterion doesn't know it */
    int32_t installed;      /* 1 if it's installed in the directory */
    int32_t available;      /* 1 if asterion_install_catalog can install it */
    int64_t size;           /* the size of the index file, in bytes, or 0 */
    int64_t stars;          /* the number of stars of the index, or 0 */
    int64_t quads;          /* the number of quads of the index, the patterns of stars it searches for, or 0 */
    double magnitude_limit; /* the magnitude of the faintest stars of the index, or NAN */
    double min_quad_arcmin; /* the size of the smallest quads, in arcminutes, or NAN */
    double max_quad_deg;    /* the size of the largest quads, in degrees, or NAN */
    char error[256];        /* why the index file can't be read, or "" */
} asterion_catalog;

/* The events of an installation, the ones of asterion --download-catalog --json. */
#define ASTERION_EVENT_STEP 1       /* a step starts: step, unit, total */
#define ASTERION_EVENT_PROGRESS 2   /* a step made progress: completed, total, fraction, bytes */
#define ASTERION_EVENT_DETAIL 3     /* a step did something worth telling: message */
#define ASTERION_EVENT_DONE 4       /* a step is over, whether it succeeded or failed */
#define ASTERION_EVENT_INFO 5       /* something which isn't part of a step happened: message */

/* The progress of an installation. The strings are UTF-8, and valid until the callback returns. */
typedef struct {
    int32_t event;          /* ASTERION_EVENT_STEP, ASTERION_EVENT_PROGRESS... */
    int32_t padding;
    int64_t completed;      /* the units of work done since the step started */
    int64_t total;          /* the units of work of the step, or -1 if it isn't known */
    int64_t bytes;          /* the bytes transferred since the step started, or -1 if the step transfers no data */
    double fraction;        /* the part of the step which is done, from 0 to 1, or NAN if it isn't known */
    const char *step;       /* what the step does, for people, such as "Downloading gaia-500 (1,234 MB)", or NULL */
    const char *unit;       /* the unit of work, in plural form, such as "MB", or NULL */
    const char *message;    /* the message of ASTERION_EVENT_DETAIL and ASTERION_EVENT_INFO, or NULL */
} asterion_progress;

/*
 * Receives the progress of an installation, on the thread which called asterion_install_catalog, with the
 * user_data given to it: returning non-zero cancels the installation, and the callback receives nothing more.
 */
typedef int (*asterion_progress_callback)(const asterion_progress *progress, void *user_data);

/* The flag which cancels asterion_solve_cancellable, which the program sets from another thread. */
typedef const volatile int32_t *asterion_cancel_flag;

/*
 * Tells if this header matches the library: 1 if each structure has the size which the library
 * reads and writes, 0 otherwise. A program checks it once, after creating the isolate.
 */
#define asterion_check_header(thread) \
    (asterion_sizeof(thread, "asterion_options") == (int) sizeof(asterion_options) \
     && asterion_sizeof(thread, "asterion_solution") == (int) sizeof(asterion_solution) \
     && asterion_sizeof(thread, "asterion_object") == (int) sizeof(asterion_object) \
     && asterion_sizeof(thread, "asterion_match") == (int) sizeof(asterion_match) \
     && asterion_sizeof(thread, "asterion_catalog") == (int) sizeof(asterion_catalog) \
     && asterion_sizeof(thread, "asterion_progress") == (int) sizeof(asterion_progress))

/* The functions, which GraalVM declares with the types above: programs include this header, not that one */
#include "libasterion.h"

#if defined(__cplusplus)
extern "C" {
#endif

/*
 * The functions again, with the names of their parameters, and what they do. Each thread passes the
 * thread which it got from graal_create_isolate or graal_attach_thread.
 */

/* Copies the version of the library, such as "0.4.0": its length. */
int asterion_version(graal_isolatethread_t *thread, char *buffer, size_t size);

/* Copies the directory of catalogs used when a function is given NULL: $ASTERION_CATALOGS, or ~/.asterion/catalogs. */
int asterion_default_catalog_directory(graal_isolatethread_t *thread, char *buffer, size_t size);

/*
 * Describes the catalogs which can be installed, then the other ones which are installed in a directory,
 * into an array of asterion_catalog: as many as its capacity. Returns the number of catalogs, which may be
 * larger than the capacity, so that a first call with a capacity of 0 counts them, or ASTERION_ERROR.
 */
int asterion_catalogs(graal_isolatethread_t *thread, const char *directory, void *catalogs, int capacity);

/*
 * Installs a catalog in a directory, the way asterion --download-catalog does, and returns when it's over:
 * 0, ASTERION_CANCELLED, or ASTERION_ERROR. The callback, or NULL, receives the progress on the calling
 * thread, one event at a time, with user_data; it cancels by returning non-zero, receives nothing more, and
 * the installation stops as soon as it can. Installing a catalog again resumes its download, if it was interrupted. A solver
 * which uses the catalog must be closed first.
 */
int asterion_install_catalog(graal_isolatethread_t *thread, const char *directory, const char *name,
                             asterion_progress_callback callback, void *user_data);

/*
 * Opens a solver, with the catalogs which are installed in a directory: they stay memory mapped until
 * asterion_close. Fails when no catalog is installed. Returns 0, or ASTERION_ERROR.
 */
int asterion_open(graal_isolatethread_t *thread, const char *directory, void **solver);

/*
 * Closes a solver: its catalogs are unmapped at once, which releases their memory. The solver can't be
 * used anymore, nor closed again, and no thread may solve with it meanwhile; the results stay valid. A
 * program closes its solvers before graal_tear_down_isolate. Returns 0, or ASTERION_ERROR.
 */
int asterion_close(graal_isolatethread_t *thread, void *solver);

/*
 * Fills an asterion_options with the defaults of the command line tool, which a program changes as it needs:
 * asterion_solve rejects options which are out of range, such as a structure filled with zeros. Returns 0,
 * or ASTERION_ERROR.
 */
int asterion_default_options(graal_isolatethread_t *thread, void *options);

/*
 * Solves an image: its pixels are copied, converted and binned in a single pass, then searched for in the
 * catalogs, so that the program can reuse them as soon as the function returns.
 *
 *     pixels      the first pixel, one sample of sample_type per pixel, in the native byte order
 *     sample_type ASTERION_UINT8, ASTERION_UINT16 or ASTERION_FLOAT32
 *     row_stride  the distance between the starts of two rows, in bytes
 *     bayer       non-zero for a raw frame of a color sensor, which is binned 2x2 at least to remove its pattern,
 *                 unless the options set the binning
 *     options     an asterion_options, or NULL for the defaults
 *     solution    an asterion_solution to fill, or NULL
 *     result      where to write a result, with the details of the solution, or NULL
 *
 * Returns ASTERION_SOLVED, ASTERION_NOT_SOLVED or ASTERION_ERROR. A result is written in the first two
 * cases, which the program frees with asterion_result_free.
 */
int asterion_solve(graal_isolatethread_t *thread, void *solver, const void *pixels, int sample_type, int width, int height,
                   long long int row_stride, int bayer, const void *options, void *solution, void **result);

/*
 * Solves an image as asterion_solve does, unless the program cancels it, from another thread: once the int32_t
 * which cancel points to isn't 0, the search stops as soon as it can, and the function returns ASTERION_CANCELLED,
 * the solution being filled as without one, and without a result; or ASTERION_SOLVED, if the image was solved
 * before. The program sets it with an atomic or a volatile write, and it stays valid until the function returns.
 * A cancel which is NULL never cancels: asterion_solve is asterion_solve_cancellable with NULL.
 */
int asterion_solve_cancellable(graal_isolatethread_t *thread, void *solver, const void *pixels, int sample_type, int width,
                               int height, long long int row_stride, int bayer, const void *options, void *solution,
                               void **result, const volatile int32_t *cancel);

/* Frees a result: NULL is ignored. */
void asterion_result_free(graal_isolatethread_t *thread, void *result);

/* Copies the result as JSON, the output of asterion --json: its length, or ASTERION_ERROR. */
int asterion_result_json(graal_isolatethread_t *thread, void *result, char *buffer, size_t size);

/* The number of deep sky objects of the image, or ASTERION_ERROR. */
int asterion_result_object_count(graal_isolatethread_t *thread, void *result);

/* Copies a deep sky object of the image into an asterion_object, the most prominent first: 0, or ASTERION_ERROR. */
int asterion_result_object(graal_isolatethread_t *thread, void *result, int index, void *object);

/* The number of stars of the image which were matched with the catalog, or ASTERION_ERROR. */
int asterion_result_match_count(graal_isolatethread_t *thread, void *result);

/* Copies a star of the image which was matched with the catalog into an asterion_match: 0, or ASTERION_ERROR. */
int asterion_result_match(graal_isolatethread_t *thread, void *result, int index, void *match);

/* The position on the sky of a pixel, in degrees, distortion included: 0, or ASTERION_ERROR. */
int asterion_result_pixel_to_sky(graal_isolatethread_t *thread, void *result, double x, double y, double *ra, double *dec);

/*
 * The pixel of a position on the sky, in degrees, distortion included: 0, or ASTERION_ERROR when the
 * position is too far from the image to be projected.
 */
int asterion_result_sky_to_pixel(graal_isolatethread_t *thread, void *result, double ra, double dec, double *x, double *y);

/* Copies the message of the last error of the calling thread: its length. */
int asterion_error(graal_isolatethread_t *thread, char *buffer, size_t size);

/* The size of a structure of this header, for asterion_check_header, or ASTERION_ERROR if there's none of this name. */
int asterion_sizeof(graal_isolatethread_t *thread, const char *name);

#if defined(__cplusplus)
}
#endif

#endif
