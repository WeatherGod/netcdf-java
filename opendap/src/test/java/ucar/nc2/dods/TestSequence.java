package ucar.nc2.dods;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.ma2.Array;
import ucar.nc2.Structure;
import ucar.nc2.Variable;
import ucar.nc2.dataset.NetcdfDataset;
import java.lang.invoke.MethodHandles;
import ucar.nc2.dataset.NetcdfDatasets;

public class TestSequence {
  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Test
  public void testSequence() {
    // The old url: "http://tsds.net/tsds/test/Scalar" is no longer valid.
    // So replaced with an equivalent.
    // Also had to replace the struct "TimeSeries" and the field "time"
    String url = "https://remotetest.unidata.ucar.edu/dts/whoi";
    try {
      NetcdfDataset ds = NetcdfDatasets.openDataset(url);
      System.out.println(ds);
      Structure struct = (Structure) ds.findVariable("emolt_sensor");
      Variable var = struct.findVariable("TEMP");
      Array arr = var.read();
      int n = (int) arr.getSize();
      int i;
      for (i = 0; arr.hasNext() && i < n; i++)
        System.out.println(arr.nextDouble());
      if (i != n) {
        System.err.println("short sequence");
        System.exit(1);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
