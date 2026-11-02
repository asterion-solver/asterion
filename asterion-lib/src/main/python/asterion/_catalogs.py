# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""The catalogs of stars, which Asterion installs once on a machine."""
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Union

from . import _native
from ._native import AsterionError

PathLike = Union[str, bytes, os.PathLike]


class InstallationCancelled(AsterionError):
    """The progress callback stopped the installation of a catalog. Installing it again resumes it."""


InstallationCancelled.__module__ = "asterion"


@dataclass(frozen=True)
class Catalog:
    """
    A catalog: one which can be installed, or one which is installed in a directory. The numbers of the index are
    None when it isn't installed.

    Attributes:
        name: its name, such as "gaia-500", or the one of its index file, without .astx
        description: what it is, and the fields of view it's for, or "" if Asterion doesn't know it
        installed: whether it's installed in the directory
        available: whether install_catalog can install it
        size: the size of its index file, in bytes
        stars: the number of stars of its index
        quads: the number of quads of its index, the patterns of stars it searches for
        magnitude_limit: the magnitude of its faintest stars
        min_quad_arcmin: the size of its smallest quads, in arcminutes
        max_quad_deg: the size of its largest quads, in degrees
        error: why its index file can't be read, or None
    """

    name: str
    description: str
    installed: bool
    available: bool
    size: Optional[int]
    stars: Optional[int]
    quads: Optional[int]
    magnitude_limit: Optional[float]
    min_quad_arcmin: Optional[float]
    max_quad_deg: Optional[float]
    error: Optional[str]


@dataclass(frozen=True)
class Progress:
    """
    An event of the installation of a catalog, the ones of asterion --download-catalog --json.

    Attributes:
        event: "step" when a step starts, "progress" when it made progress, "detail" when it did something worth
            telling, "done" when it's over, whether it succeeded or failed, or "info" for what isn't part of a step
        step: what the step does, for people, such as "Downloading gaia-500 (1,234 MB)", or None for "info"
        unit: the unit of the work of the step, in plural form, such as "MB"
        completed: the units of work done since the step started
        total: the units of work of the step, or None if it isn't known
        fraction: the part of the step which is done, from 0 to 1, or None if it isn't known
        bytes: the bytes transferred since the step started, or None if the step transfers no data
        message: the message of the "detail" and "info" events
    """

    event: str
    step: Optional[str]
    unit: Optional[str]
    completed: int
    total: Optional[int]
    fraction: Optional[float]
    bytes: Optional[int]
    message: Optional[str]


def version() -> str:
    """The version of the library, such as "0.4.0"."""
    return _native.string("asterion_version")


def default_catalog_directory() -> Path:
    """The directory of the catalogs which the functions use by default: $ASTERION_CATALOGS, or ~/.asterion/catalogs,
    the one of the command line tool."""
    return Path(_native.string("asterion_default_catalog_directory"))


def catalogs(directory: Optional[PathLike] = None) -> list[Catalog]:
    """The catalogs which can be installed, then the other ones which are installed in a directory, by default the one
    of the command line tool."""
    directory = _native.path(directory)
    count = _native.check(_native.call("asterion_catalogs", directory, None, 0))
    described = (_native.Catalog * count)()
    # an index file may have been removed meanwhile
    count = min(count, _native.check(_native.call("asterion_catalogs", directory, described, count)))
    return [_catalog(catalog) for catalog in described[:count]]


def install_catalog(name: str, directory: Optional[PathLike] = None,
                    progress: Optional[Callable[[Progress], Optional[bool]]] = None) -> None:
    """
    Installs a catalog, such as "tycho2" or "gaia-500", the way asterion --download-catalog does, and returns when
    it's over. An interrupted installation resumes its download the next time. Ctrl-C cancels it, between two events
    of its progress. A solver which uses the catalog must be closed before installing it again.

    Args:
        name: the name of the catalog, as catalogs() lists them
        directory: the directory of the catalogs, by default the one of the command line tool
        progress: receives the progress, on the calling thread: returning True cancels the installation, which raises
            InstallationCancelled. An exception of the callback, such as KeyboardInterrupt, cancels it too, and is
            raised once the installation stopped.

    Raises:
        InstallationCancelled: if the callback cancelled the installation
        AsterionError: if the catalog is unknown, or can't be installed
    """
    failure = None

    def receive(event, user_data):
        # Python code which runs during the installation: KeyboardInterrupt is raised there
        nonlocal failure
        try:
            return 1 if progress is not None and progress(_progress(event.contents)) else 0
        except BaseException as e:
            failure = e
            return 1

    # the callback must stay referenced while the library may call it
    callback = _native.ProgressCallback(receive)
    status = _native.call("asterion_install_catalog", _native.path(directory), name.encode("utf-8"), callback, None)
    if failure is not None:
        raise failure
    if status == _native.CANCELLED:
        raise InstallationCancelled(f"The installation of {name} was cancelled")
    _native.check(status)


def _catalog(catalog):
    def number(value):
        return _native.optional(value) if catalog.installed else None

    return Catalog(
        name=_native.text(catalog.name),
        description=_native.text(catalog.description),
        installed=bool(catalog.installed),
        available=bool(catalog.available),
        size=catalog.size if catalog.installed else None,
        stars=catalog.stars if catalog.installed else None,
        quads=catalog.quads if catalog.installed else None,
        magnitude_limit=number(catalog.magnitude_limit),
        min_quad_arcmin=number(catalog.min_quad_arcmin),
        max_quad_deg=number(catalog.max_quad_deg),
        error=_native.text(catalog.error) or None,
    )


def _progress(event):
    return Progress(
        event=_native.EVENTS.get(event.event, str(event.event)),
        step=_native.text(event.step),
        unit=_native.text(event.unit),
        completed=event.completed,
        total=event.total if event.total >= 0 else None,
        fraction=_native.optional(event.fraction),
        bytes=event.bytes if event.bytes >= 0 else None,
        message=_native.text(event.message),
    )
