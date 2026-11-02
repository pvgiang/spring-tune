def log = new File(basedir, 'build.log').text
assert log.contains('ST-HIKARI-001')
assert log.contains('connectionTimeout is 0')
assert log.contains('SpringTune: 1 finding(s) at or above ERROR')
def sarif = new File(basedir, 'target/springtune/springtune-report.sarif')
assert sarif.isFile()
assert sarif.text.contains('"ruleId" : "ST-HIKARI-001"')
assert new File(basedir, 'target/springtune/springtune-report.md').isFile()
return true
