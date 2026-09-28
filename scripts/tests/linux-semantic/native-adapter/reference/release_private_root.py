"""Shared no-follow, runner-owned temporary-root validation (no writes)."""
import os
import stat


def require_safe_directory(value, *, selected=False):
    mode = stat.S_IMODE(value.st_mode)
    if not stat.S_ISDIR(value.st_mode) or mode & 0o022:
        raise RuntimeError("unsafe temporary root chain")
    if value.st_uid not in {0, os.geteuid()}:
        raise RuntimeError("untrusted temporary root owner")
    if selected and (value.st_uid != os.geteuid() or mode & 0o700 != 0o700):
        raise RuntimeError("temporary root is not runner-owned and accessible")


def open_canonical_root(path):
    if (
        not path
        or not os.path.isabs(path)
        or path != os.path.normpath(path)
        or path != os.path.realpath(path)
    ):
        raise RuntimeError("temporary root is not canonical")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptor = os.open("/", flags)
    try:
        require_safe_directory(os.fstat(descriptor))
        components = [component for component in path.split("/") if component]
        for index, component in enumerate(components):
            next_descriptor = os.open(component, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = next_descriptor
            require_safe_directory(
                os.fstat(descriptor), selected=index == len(components) - 1
            )
        if not components:
            require_safe_directory(os.fstat(descriptor), selected=True)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise
