import sys
from typing import Optional
import shutil
import subprocess


class WvletCompiler():
    def __init__(self, executable_path: Optional[str] = None, target: Optional[str] = None):
        if executable_path:
            if shutil.which(executable_path) is None:
                raise ValueError(f"Invalid executable_path: {executable_path}")
            self.path = executable_path
            return
        # To make self.path non-optional type, first declare optional path
        path = shutil.which("wvlet")
        if path is None:
            raise NotImplementedError("This binding currently requires wvc executable")
        self.path = path
        self.target = target


    def compile(self, query: str) -> str:
        command = [self.path, "compile"]
        if self.target:
            command.append(f"--target:{self.target}")
        command.append(query)
        process = subprocess.run(command, capture_output=True, text=True)
        print(process.stdout, end="")
        print(process.stderr, file=sys.stderr, end="")
        if process.returncode != 0:
            raise ValueError("Failed to compile")
        return "\n".join(process.stdout.split("\n")[1:])
