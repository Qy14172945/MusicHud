package indi.etern.musichud.platform.mod.forge.network;

import indi.etern.musichud.network.IClientNetworkService;
import indi.etern.musichud.network.payloads.C2SPayload;

@SuppressWarnings("unused")
public class ForgeClientNetworkService implements IClientNetworkService {
    private static volatile ForgeClientNetworkService instance;

    public static ForgeClientNetworkService getInstance() {
        if (instance == null) {
            synchronized (ForgeClientNetworkService.class) {
                if (instance == null) {
                    instance = new ForgeClientNetworkService();
                }
            }
        }
        return instance;
    }

    @Override
    public void sendToNetworkServer(C2SPayload payload) {
        ForgeNetworkManager.getInstance().sendToServer(payload);
    }
}
