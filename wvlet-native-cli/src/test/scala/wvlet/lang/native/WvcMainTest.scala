package wvlet.lang.native

import wvlet.airspec.AirSpec

class WvcMainTest extends AirSpec:
  test("run command") {
    WvcMain.main(Array("-c", "select 1"))
  }