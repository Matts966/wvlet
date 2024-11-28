#
# Copyright 2024 wvlet.org
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


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
