package dev.jaspreet.printserver.render;

/** Private same-app IPC boundary for the disposable native renderer process. */
interface IRendererService {
    int getRendererPid();
    String render(
        String inputPath,
        String outputPath,
        String documentFormat,
        String quality,
        String colorMode,
        String profileId
    );
}
