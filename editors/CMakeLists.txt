
if(CMAKE_SYSTEM_NAME MATCHES "Linux")
	option(SC_EL "Build emacs-based IDE." ON)
    option(SC_VIM "Build vim-based IDE." ON)
else()
	option(SC_EL "Build emacs-based IDE." OFF)
    option(SC_VIM "Build vim-based IDE." OFF)
endif()

if(SC_IDE)
	message(STATUS "Building the Qt IDE")
	add_subdirectory(sc-ide)
endif()

if(SC_EL)
	add_subdirectory(sc-el)
endif()

if(SC_VIM)
	if(NO_GPL3)
		message(WARNING "SC_VIM requested, but NO_GPL3 flag is on, so not activated.")
	else()
		add_subdirectory(scvim)
	endif()
endif()

