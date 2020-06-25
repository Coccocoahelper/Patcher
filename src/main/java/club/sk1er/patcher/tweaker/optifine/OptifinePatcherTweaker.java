/*
 * Copyright © 2020 by Sk1er LLC
 *
 * All rights reserved.
 *
 * Sk1er LLC
 * 444 S Fulton Ave
 * Mount Vernon, NY
 * sk1er.club
 */

package club.sk1er.patcher.tweaker.optifine;

import java.util.Map;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.MCVersion(ForgeVersion.mcVersion)
public class OptifinePatcherTweaker implements IFMLLoadingPlugin {

  @Override
  public String[] getASMTransformerClass() {
    return new String[] {OptifineClassTransformer.class.getName()};
  }

  @Override
  public String getModContainerClass() {
    return null;
  }

  @Override
  public String getSetupClass() {
    return null;
  }

  @Override
  public void injectData(Map<String, Object> data) {}

  @Override
  public String getAccessTransformerClass() {
    return null;
  }
}
