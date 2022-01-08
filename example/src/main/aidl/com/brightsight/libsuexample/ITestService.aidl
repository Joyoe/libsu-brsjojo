// ITestService.aidl
package com.brightsight.libsuexample;

// Declare any non-default types here with import statements

interface ITestService {
    int getPid();
    int getUid();
    String readCmdline();
}
