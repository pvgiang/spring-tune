def log = new File(basedir, 'build.log').text
assert log.contains('ST-HIKARI-001')
assert log.contains('BUILD SUCCESS')
assert new File(basedir, 'target/springtune/springtune-report.json').text.contains('"error" : 1')
return true
