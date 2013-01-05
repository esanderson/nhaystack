//
// Copyright (c) 2011, Brian Frank
// Licensed under the Academic Free License version 3.0
//
// History:
//   06 Jun 2011  Brian Frank  Creation
//
package nhaystack.test;

import java.lang.reflect.*;
import haystack.*;
import haystack.client.*;
import haystack.test.*;

/**
 * Simple test harness to avoid pulling in dependencies.
 */
public abstract class NTest extends Test
{
//////////////////////////////////////////////////////////////////////////
// Test Case List
//////////////////////////////////////////////////////////////////////////

  public static String[] TESTS =
  {
    "nhaystack.test.NSimpleClientTest",
  };

//////////////////////////////////////////////////////////////////////////
// Main
//////////////////////////////////////////////////////////////////////////

  public static void main(String[] args)
  {
    String pattern = null;
    for (int i=0; i<args.length; ++i)
    {
      String arg = args[i];
      if (arg.startsWith("-"))
      {
        if (arg.equals("-v")) verbose = true;
        else println("Uknown option: " + arg);
      }
      else if (pattern == null)
      {
        pattern = arg;
      }
    }
    runTests(TESTS, pattern);
  }
}
