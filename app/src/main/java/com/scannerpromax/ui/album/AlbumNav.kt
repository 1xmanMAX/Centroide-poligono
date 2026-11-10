package com.scannerpromax.ui.album

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.scannerpromax.di.AppContainer
import com.scannerpromax.ui.navigation.Routes

/** Pantallas del álbum (lista, editor, cámara del cromo y recorte) en el grafo de navegación de la app. */
fun NavGraphBuilder.albumGraph(container: AppContainer, nav: NavHostController) {
    composable(Routes.ALBUMS) { entry ->
        AlbumListScreen(
            container = container,
            onBack = { entry.ifActive(nav) { nav.popBackStack() } },
            onOpen = { id -> entry.ifActive(nav) { nav.navigate(Routes.album(id)) { launchSingleTop = true } } },
        )
    }
    composable(Routes.ALBUM, arguments = listOf(navArgument("albumId") { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString("albumId").orEmpty()
        AlbumEditorScreen(
            container = container,
            albumId = id,
            onBack = { entry.ifActive(nav) { nav.popBackStack() } },
            onScanSlot = { p, s -> entry.ifActive(nav) { nav.navigate(Routes.albumCamera(id, p, s)) } },
            onCropSlot = { p, s, src -> entry.ifActive(nav) { nav.navigate(Routes.albumCrop(id, p, s, src)) } },
            onOpenDocument = { docId -> entry.ifActive(nav) { nav.navigate(Routes.review(docId)) } },
        )
    }
    composable(
        Routes.ALBUM_CAMERA,
        arguments = listOf(
            navArgument("albumId") { type = NavType.StringType },
            navArgument("pageId") { type = NavType.StringType },
            navArgument("slotId") { type = NavType.StringType },
        ),
    ) { entry ->
        val a = entry.arguments
        AlbumCameraScreen(
            container = container,
            albumId = a?.getString("albumId").orEmpty(),
            pageId = a?.getString("pageId").orEmpty(),
            slotId = a?.getString("slotId").orEmpty(),
            onBack = { entry.ifActive(nav) { nav.popBackStack() } },
        )
    }
    composable(
        Routes.ALBUM_CROP,
        arguments = listOf(
            navArgument("albumId") { type = NavType.StringType },
            navArgument("pageId") { type = NavType.StringType },
            navArgument("slotId") { type = NavType.StringType },
            navArgument("src") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) { entry ->
        val a = entry.arguments
        StickerCropScreen(
            container = container,
            albumId = a?.getString("albumId").orEmpty(),
            pageId = a?.getString("pageId").orEmpty(),
            slotId = a?.getString("slotId").orEmpty(),
            source = a?.getString("src")?.takeIf { it.isNotBlank() },
            onBack = { entry.ifActive(nav) { nav.popBackStack() } },
        )
    }
}

private inline fun NavBackStackEntry.ifActive(nav: NavHostController, action: () -> Unit) {
    if (nav.currentBackStackEntry?.id == id && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action()
}
