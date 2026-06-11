# Paquete RPM del receptor CamStream (Fedora y derivados).
# Se construye en CI con:
#   rpmbuild -bb packaging/camstream-receiver.spec \
#     --define "_topdir $PWD/rpmbuild" --define "srcdir $PWD"

Name:           camstream-receiver
Version:        0.5.0
Release:        1%{?dist}
Summary:        Usa tu teléfono Android como webcam (receptor de CamStream)
License:        MIT
URL:            https://github.com/sukiisanz/camstream
BuildArch:      noarch

Requires:       python3
Requires:       python3-gobject
Requires:       gtk3
Requires:       gstreamer1
Requires:       gstreamer1-plugins-base
Requires:       gstreamer1-plugins-good
Recommends:     python3-zeroconf
Recommends:     akmod-v4l2loopback
Recommends:     android-tools
Recommends:     avahi-tools

%description
Receptor de la app Android CamStream: encuentra el teléfono por WiFi
(mDNS) o por cable USB (adb), recibe el video RTSP y lo convierte en una
cámara virtual (/dev/video10) que Zoom, Meet o Teams usan como una
webcam normal. Incluye interfaz gráfica (camstream-receiver-gui) y
versión de terminal (camstream-receiver).

La primera vez, el botón "Instalar dependencias" de la interfaz deja
configurado el módulo v4l2loopback (desde RPM Fusion) y el resto de
piezas del sistema.

%install
install -D -m0644 %{srcdir}/receiver/camstream_receiver.py \
    %{buildroot}%{_datadir}/camstream-receiver/camstream_receiver.py
install -D -m0644 %{srcdir}/receiver/camstream_gui.py \
    %{buildroot}%{_datadir}/camstream-receiver/camstream_gui.py
install -D -m0755 %{srcdir}/packaging/bin/camstream-receiver \
    %{buildroot}%{_bindir}/camstream-receiver
install -D -m0755 %{srcdir}/packaging/bin/camstream-receiver-gui \
    %{buildroot}%{_bindir}/camstream-receiver-gui
install -D -m0755 %{srcdir}/packaging/camstream-receiver-setup \
    %{buildroot}%{_libexecdir}/camstream-receiver-setup
install -D -m0644 %{srcdir}/packaging/camstream-receiver.desktop \
    %{buildroot}%{_datadir}/applications/camstream-receiver.desktop
install -D -m0644 %{srcdir}/CamStream-logo.png \
    %{buildroot}%{_datadir}/pixmaps/camstream-receiver.png

%files
%{_bindir}/camstream-receiver
%{_bindir}/camstream-receiver-gui
%{_libexecdir}/camstream-receiver-setup
%{_datadir}/camstream-receiver/
%{_datadir}/applications/camstream-receiver.desktop
%{_datadir}/pixmaps/camstream-receiver.png

%changelog
* Wed Jun 11 2026 CamStream <perezsanzfamilia@gmail.com> - 0.5.0-1
- Primer paquete RPM: GUI GTK, instalación de dependencias guiada
