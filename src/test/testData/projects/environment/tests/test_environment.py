import os


def test_environment_variable_reaches_pytest():
    assert os.environ["PHR_TEST_VALUE"] == "ü 名 with spaces"
